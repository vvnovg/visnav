package io.visnav.app.obd

import io.visnav.core.ElmChannel
import io.visnav.core.ElmSession
import io.visnav.core.ObdSpeedEvent
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * Отмена подключения: канал регистрирует создаваемый сокет или BluetoothGatt, а [cancel] из stop() закрывает его.
 * Блокирующий BluetoothSocket.connect() не прерывается Thread.interrupt(), без этого stop() ждал бы 10–12 с.
 */
class ObdCancel {
    private val pending = AtomicReference<Closeable?>(null)
    @Volatile var cancelled = false
        private set

    /** Регистрирует ресурс текущего подключения; после отмены закрывает его сразу. */
    fun register(c: Closeable) {
        pending.set(c)
        if (cancelled) closeQuietly(c)
    }

    fun cancel() {
        cancelled = true
        pending.getAndSet(null)?.let(::closeQuietly)
    }

    private fun closeQuietly(c: Closeable) { runCatching { c.close() } }
}

/**
 * Сессия ELM327 на отдельном потоке «obd». [open] подключает SppChannel или BleChannel; [nowMs] — шкала датчиков.
 * Скорость → [onSpeed] (журнал и буфер упорядочения), состояние → [onUi]. Исключение колбэка (ElmSession.run()
 * пробрасывает его) пишется в состояние «ошибка приложения: …», сессия сама не перезапускается.
 */
class ObdClient(
    private val deviceName: String?,
    open: (ObdCancel) -> ElmChannel,
    nowMs: () -> Double,
    private val onSpeed: (ObdSpeedEvent) -> Unit,
    private val onUi: ((ObdUi?) -> ObdUi) -> Unit,
) {
    private val cancel = ObdCancel()
    @Volatile private var stopped = false
    private val session = ElmSession(
        open = { open(cancel) },
        nowMs = nowMs,
        sleep = { Thread.sleep(it) },
        onSpeed = ::deliverSpeed,
        onState = { s, msg -> onUi { (it ?: ObdUi("", deviceName = deviceName)).copy(state = obdStateLabel(s, msg)) } },
    )
    private val thread = Thread({
        try {
            session.run()
        } catch (t: Throwable) {
            onUi { (it ?: ObdUi("", deviceName = deviceName)).copy(state = "ошибка приложения: ${t.message ?: t.javaClass.simpleName}") }
        }
    }, "obd").apply { isDaemon = true }

    /** Скорость от сессии: в журнал и на экран; после stop() отбрасывается. */
    internal fun deliverSpeed(e: ObdSpeedEvent) {
        if (stopped) return
        onSpeed(e)
        onUi { (it ?: ObdUi("опрос", deviceName = deviceName)).copy(lastKmh = e.kmh, rttMs = e.rttMs) }
    }

    fun start() {
        onUi { ObdUi("подключение", deviceName = deviceName) }
        thread.start()
    }

    /** Останавливает сессию и отменяет подключение; события после stop() не передаются. */
    fun stop() {
        stopped = true
        cancel.cancel()
        session.stop()
    }

    /** Ждёт завершения потока; false — не завершился за [timeoutMs]. */
    fun join(timeoutMs: Long): Boolean {
        if (thread.state == Thread.State.NEW) return true
        thread.join(timeoutMs)
        return !thread.isAlive
    }
}
