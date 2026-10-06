package io.visnav.app.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.visnav.core.ElmChannel
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ELM327 по BLE: сервис FFF0 (уведомления FFF1, запись FFF2), иначе Nordic UART. Все шаги GATT — на колбэках
 * с CountDownLatch и таймаутами; уведомления копятся в [PromptBuffer] до ">" (см. [appendUntilPrompt]).
 */
class BleChannel private constructor() : ElmChannel, Closeable {
    private val buffer = PromptBuffer()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var chunk = 20

    @Volatile private var connected = CountDownLatch(1)
    /** Шаг GATT, чей колбэк ждём; колбэки других шагов (поздний onMtuChanged и т. п.) игнорируются. */
    private enum class Step { NONE, MTU, DISCOVER, DESCRIPTOR, WRITE }

    private val stepLock = Any()
    private var expected = Step.NONE
    @Volatile private var step = CountDownLatch(1)
    @Volatile private var stepOk = false
    @Volatile private var mtu = 23
    @Volatile private var disconnected = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                disconnected = true
                buffer.fail(IOException("BLE: отключено (status $status)"))
                connected.countDown()
                step.countDown()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = finish(Step.MTU, status) {
            this@BleChannel.mtu = mtu
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(Step.DISCOVER, status) {}

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(Step.DESCRIPTOR, status) {}

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish(Step.WRITE, status) {}

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            buffer.append(String(value, Charsets.ISO_8859_1))
        }

        @Deprecated("До Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { buffer.append(String(it, Charsets.ISO_8859_1)) }
        }
    }

    private inline fun finish(s: Step, status: Int, onSuccess: () -> Unit) = synchronized(stepLock) {
        if (s != expected) return@synchronized
        expected = Step.NONE
        stepOk = status == BluetoothGatt.GATT_SUCCESS
        if (stepOk) onSuccess()
        step.countDown()
    }

    /** Запускает шаг GATT [s] и ждёт его колбэк; false — неудача или таймаут. */
    private fun await(s: Step, timeoutMs: Long, start: () -> Boolean): Boolean {
        if (disconnected) throw IOException("BLE: отключено")
        synchronized(stepLock) {
            expected = s
            step = CountDownLatch(1)
            stepOk = false
        }
        try {
            if (!start()) return false
            val done = step.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (disconnected) throw IOException("BLE: отключено")
            return done && stepOk
        } finally {
            synchronized(stepLock) { expected = Step.NONE }
        }
    }

    // Разрешение проверено в connect() (canConnect); SecurityException при отзыве — там же, как IOException.
    @SuppressLint("MissingPermission")
    private fun setup(context: Context, device: BluetoothDevice, cancel: ObdCancel) {
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("BLE: connectGatt не удался")
        gatt = g
        cancel.register(this)
        if (!connected.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw IOException("BLE: нет подключения")
        if (disconnected) throw IOException("BLE: отключено при подключении")
        // MTU 185; при неудаче — 23 (запись по 20 байт).
        chunk = if (await(Step.MTU, STEP_TIMEOUT_MS) { g.requestMtu(185) }) (mtu - 3).coerceAtLeast(20) else 20
        if (!await(Step.DISCOVER, DISCOVER_TIMEOUT_MS) { g.discoverServices() }) throw IOException("BLE: сервисы не найдены")
        val (notify, write) = findChars(g) ?: throw IOException("BLE: нет сервиса FFF0 или Nordic UART")
        writeChar = write
        if (!g.setCharacteristicNotification(notify, true)) throw IOException("BLE: уведомления не включены")
        val cccd = notify.getDescriptor(CCCD) ?: throw IOException("BLE: нет CCCD")
        val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        if (!await(Step.DESCRIPTOR, STEP_TIMEOUT_MS) { writeDescriptor(g, cccd, value) }) throw IOException("BLE: запись CCCD не удалась")
    }

    private fun findChars(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for ((svc, notify, write) in SERVICES) {
            val s = g.getService(svc) ?: continue
            val n = s.getCharacteristic(notify) ?: continue
            val w = s.getCharacteristic(write) ?: continue
            return n to w
        }
        return null
    }

    override fun write(cmd: String) {
        val g = gatt ?: throw IOException("BLE: закрыт")
        val c = writeChar ?: throw IOException("BLE: нет характеристики записи")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val bytes = "$cmd\r".toByteArray(Charsets.ISO_8859_1)
        try {
            for (from in bytes.indices step chunk) {
                val part = bytes.copyOfRange(from, minOf(from + chunk, bytes.size))
                if (!await(Step.WRITE, STEP_TIMEOUT_MS) { writeCharacteristic(g, c, part, type) }) throw IOException("BLE: запись не удалась")
            }
        } catch (e: SecurityException) {
            throw IOException("нет разрешения Bluetooth: ${e.message}", e)
        }
    }

    override fun readUntilPrompt(timeoutMs: Long): String? = buffer.take(timeoutMs)

    override fun drain(timeoutMs: Long) = buffer.drain(timeoutMs)

    override fun clearInput() = buffer.clear()

    override fun close() {
        val g = gatt ?: return
        gatt = null
        disconnected = true
        buffer.fail(IOException("BLE: закрыт"))
        step.countDown()
        connected.countDown()
        try {
            g.disconnect()
            g.close()
        } catch (_: SecurityException) {
            // Разрешение отозвано во время записи: закрывать уже нечего.
        }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private val SERVICES = listOf(
            Triple(uuid16("FFF0"), uuid16("FFF1"), uuid16("FFF2")),
            Triple(
                UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
                UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
            ),
        )
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val DISCOVER_TIMEOUT_MS = 10_000L
        private const val STEP_TIMEOUT_MS = 3_000L

        private fun uuid16(s: String): UUID = UUID.fromString("0000$s-0000-1000-8000-00805F9B34FB")

        /** Подключается к BLE-адаптеру [addr]; BluetoothGatt регистрируется в [cancel] (stop() его закроет). */
        fun connect(context: Context, addr: String, cancel: ObdCancel): BleChannel {
            if (!canConnect(context)) throw IOException("нет разрешения Bluetooth")
            val ch = BleChannel()
            try {
                ch.setup(context, bluetoothAdapter(context).getRemoteDevice(addr), cancel)
                return ch
            } catch (e: SecurityException) {
                ch.close()
                throw IOException("нет разрешения Bluetooth: ${e.message}", e)
            } catch (e: Exception) {
                ch.close()
                throw e
            }
        }

        // Разрешение проверено в connect(); SecurityException в write() — IOException.
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = value
                g.writeDescriptor(d)
            }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = value
                g.writeCharacteristic(c)
            }
    }
}

/**
 * Поиск BLE-адаптеров ELM327 на 10 с ([isObdName]). Имя — из рекламы (scanRecord), без BLUETOOTH_CONNECT.
 * Колбэки — на главном потоке.
 */
class ObdScanner(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null
    private var stopTask: Runnable? = null

    /** Начинает поиск: [onFound] — каждое новое устройство, [onDone] — конец или ошибка (сообщение). */
    fun start(onFound: (ObdDevice) -> Unit, onDone: (String?) -> Unit) {
        stop()
        val canScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!canScan) { onDone("нет разрешения Bluetooth"); return }
        val scanner = try {
            bluetoothAdapter(context).bluetoothLeScanner ?: throw IOException("BLE недоступен")
        } catch (e: IOException) {
            onDone(e.message); return
        }
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName
                val addr = result.device.address
                if (isObdName(name) && seen.add(addr)) onFound(ObdDevice(addr, name!!))
            }

            override fun onScanFailed(errorCode: Int) {
                // Снимаем таймер 10 с, иначе его onDone(null) затрёт сообщение об ошибке.
                stopTask?.let { handler.removeCallbacks(it) }
                stopTask = null
                callback = null
                onDone("ошибка поиска BLE: $errorCode")
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: SecurityException) {
            onDone("нет разрешения Bluetooth"); return
        }
        callback = cb
        val r = Runnable { stop(); onDone(null) }
        stopTask = r
        handler.postDelayed(r, SCAN_MS)
    }

    // Поиск запускается только с BLUETOOTH_SCAN (см. start()); отзыв разрешения — SecurityException, перехвачен.
    @SuppressLint("MissingPermission")
    fun stop() {
        stopTask?.let { handler.removeCallbacks(it) }
        stopTask = null
        val cb = callback ?: return
        callback = null
        try {
            bluetoothAdapter(context).bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
            // Bluetooth выключили или отозвали разрешение — поиск уже остановлен.
        }
    }

    private companion object {
        const val SCAN_MS = 10_000L
    }
}
