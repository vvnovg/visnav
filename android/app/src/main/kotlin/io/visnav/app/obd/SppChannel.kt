package io.visnav.app.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.visnav.core.ElmChannel
import java.io.IOException
import java.util.UUID

/** Адаптер Bluetooth телефона или IOException («нет Bluetooth», «Bluetooth выключен»). */
internal fun bluetoothAdapter(context: Context): BluetoothAdapter {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("нет Bluetooth")
    if (!adapter.isEnabled) throw IOException("Bluetooth выключен")
    return adapter
}

internal fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Есть ли BLUETOOTH_CONNECT (до Android 12 выдаётся при установке). */
internal fun canConnect(context: Context) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)

/** Сопряжённые устройства для выбора адаптера Bluetooth Classic; без разрешения — пусто. */
internal fun bondedObdDevices(context: Context): List<ObdDevice> {
    if (!canConnect(context)) return emptyList()
    return try {
        bluetoothAdapter(context).bondedDevices.orEmpty()
            .map { ObdDevice(it.address, it.name ?: it.address) }
            .sortedBy { it.name.lowercase() }
    } catch (_: IOException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }
}

/**
 * ELM327 по Bluetooth Classic (SPP/RFCOMM). Поток «obd-spp-read» читает сокет в [PromptBuffer]; обрыв чтения —
 * IOException в следующем readUntilPrompt.
 */
class SppChannel private constructor(private val socket: BluetoothSocket) : ElmChannel {
    private val buffer = PromptBuffer()
    private val out = socket.outputStream
    private val reader = Thread({
        val input = socket.inputStream
        val bytes = ByteArray(256)
        try {
            while (true) {
                val n = input.read(bytes)
                if (n < 0) throw IOException("SPP: соединение закрыто")
                buffer.append(String(bytes, 0, n, Charsets.ISO_8859_1))
            }
        } catch (e: IOException) {
            buffer.fail(e)
        }
    }, "obd-spp-read").apply { isDaemon = true; start() }

    override fun write(cmd: String) {
        out.write("$cmd\r".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    override fun readUntilPrompt(timeoutMs: Long): String? = buffer.take(timeoutMs)

    override fun drain(timeoutMs: Long) = buffer.drain(timeoutMs)

    override fun clearInput() = buffer.clear()

    override fun close() {
        runCatching { socket.close() }
        reader.interrupt()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * Подключается к сопряжённому адаптеру [addr]: защищённый RFCOMM-сокет, при неудаче — незащищённый.
         * Создаваемый сокет регистрируется в [cancel], чтобы stop() прервал блокирующий connect().
         */
        fun connect(context: Context, addr: String, cancel: ObdCancel): SppChannel {
            if (!canConnect(context)) throw IOException("нет разрешения Bluetooth")
            try {
                val adapter = bluetoothAdapter(context)
                val device = adapter.bondedDevices.orEmpty().firstOrNull { it.address == addr }
                    ?: throw IOException("адаптер $addr не сопряжён")
                // Поиск устройств замедляет RFCOMM; отмена требует BLUETOOTH_SCAN на Android 12+.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) {
                    adapter.cancelDiscovery()
                }
                // Защищённый сокет, затем незащищённый, затем канал 1 напрямую (клоны без записи SDP).
                val attempts = listOf<() -> BluetoothSocket>(
                    { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                    { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                    { rfcommChannel1(device) },
                )
                var failure: Exception? = null
                for (create in attempts) {
                    try {
                        return SppChannel(open(cancel, create))
                    } catch (e: IOException) {
                        if (cancel.cancelled) throw e
                        failure = failure?.apply { addSuppressed(e) } ?: e
                    } catch (e: ReflectiveOperationException) { // createRfcommSocket недоступен
                        failure = failure?.apply { addSuppressed(e) } ?: e
                    }
                }
                throw failure as? IOException ?: IOException("SPP: подключение не удалось", failure)
            } catch (e: SecurityException) {
                throw IOException("нет разрешения Bluetooth: ${e.message}", e)
            }
        }

        /** Скрытый BluetoothDevice.createRfcommSocket(1) — запасной путь для адаптеров без SPP в SDP. */
        @SuppressLint("DiscouragedPrivateApi")
        private fun rfcommChannel1(device: BluetoothDevice): BluetoothSocket =
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket

        // Разрешение проверено в connect() (canConnect); SecurityException там перехвачен.
        @SuppressLint("MissingPermission")
        private fun open(cancel: ObdCancel, create: () -> BluetoothSocket): BluetoothSocket {
            val s = create()
            cancel.register(s)
            try {
                s.connect()
            } catch (e: IOException) {
                runCatching { s.close() }
                throw e
            }
            return s
        }
    }
}
