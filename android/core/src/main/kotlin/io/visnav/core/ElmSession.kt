package io.visnav.core

import java.util.concurrent.atomic.AtomicReference

/** Транспорт до адаптера ELM327 (Bluetooth Classic или BLE). */
interface ElmChannel {
    /** Отправляет команду, добавляя "\r". */
    fun write(cmd: String)

    /**
     * Ответ до ">" включительно или null по таймауту. Часть ответа, пришедшая до таймаута, остаётся
     * в буфере канала и попадёт в следующее чтение — после null вызывающий делает [drain].
     */
    fun readUntilPrompt(timeoutMs: Long): String?

    /** Вычитывает и отбрасывает запоздавший вывод адаптера: читает до первого таймаута, не больше 5 раз. */
    fun drain(timeoutMs: Long = 300) {
        repeat(5) { readUntilPrompt(timeoutMs) ?: return }
    }

    fun close()
}

enum class ElmState { CONNECTING, INIT, SEARCHING, POLLING, NO_DATA, ERROR, RECONNECT_WAIT, STOPPED }

/**
 * Диалог с ELM327: подключение, инициализация, опрос скорости (010D), переподключение.
 * Ошибка, таймаут, неразобранный ответ или сброс адаптера при опросе — счётчик; 5 подряд — закрытие
 * канала и пауза 2/5/10/10… с; счётчик и паузу сбрасывает только удачный ответ.
 * Исключения из [open] и канала — причина переподключения. Исключения из [onSpeed]/[onState] ошибкой
 * связи не считаются: канал закрывается, выдаётся STOPPED, и исключение выходит из [run] как есть.
 */
class ElmSession(
    private val open: () -> ElmChannel,
    private val nowMs: () -> Double,
    private val sleep: (Long) -> Unit,
    private val onSpeed: (ObdSpeedEvent) -> Unit,
    private val onState: (ElmState, String?) -> Unit,
    private val minIntervalMs: Long = 200,
) {
    @Volatile private var running = true
    private val channel = AtomicReference<ElmChannel?>(null)
    private val lock = Any()
    private var runner: Thread? = null
    private var stopRequested = false
    private var backoffIdx = 0
    private var state: ElmState? = null

    /** Исключение из колбэка; разворачивается в run(). */
    private class CallbackFailure(cause: Throwable) : RuntimeException(cause)

    /** Блокирующий цикл до stop(): подключение, INIT, опрос, переподключение. */
    fun run() {
        synchronized(lock) { runner = Thread.currentThread() }
        try {
            while (running) {
                val reason = try {
                    session()
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: CallbackFailure) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                closeChannel()
                if (!running) break
                val pause = BACKOFF_MS[minOf(backoffIdx, BACKOFF_MS.size - 1)]
                backoffIdx++
                setState(ElmState.ERROR, reason)
                setState(ElmState.RECONNECT_WAIT, "$pause ms")
                sleep(pause)
            }
        } catch (e: InterruptedException) {
            if (running) { // прерывание извне, не через stop(): сохраняем флаг потока
                running = false
                Thread.currentThread().interrupt()
            }
        } catch (e: CallbackFailure) {
            running = false
            throw e.cause!!
        } finally {
            closeChannel()
            synchronized(lock) {
                runner = null
                if (stopRequested) Thread.interrupted() // снимает прерывание, выставленное stop()
            }
            state = ElmState.STOPPED
            onState(ElmState.STOPPED, null)
        }
    }

    /** Прерывает run(): флаг, закрытие канала, прерывание паузы. Можно вызывать из любого потока. */
    fun stop() {
        running = false
        closeChannel()
        synchronized(lock) {
            stopRequested = true
            val r = runner
            if (r != null && r != Thread.currentThread()) r.interrupt()
        }
    }

    /** Одно подключение; возвращает причину для переподключения (или null, если остановлено). */
    private fun session(): String? {
        setState(ElmState.CONNECTING, null)
        val ch = open()
        channel.set(ch)
        if (!running) return null
        initialize(ch)?.let { return if (running) it else null }
        if (!running) return null
        setState(ElmState.POLLING, null)
        var errors = 0
        while (running) {
            val t0 = nowMs()
            ch.write(Elm327.SPEED)
            val r = ch.readUntilPrompt(RESPONSE_TIMEOUT_MS)
            val rtt = nowMs() - t0
            if (!running) return null
            if (r == null) ch.drain()
            if (r != null && Elm327.isReset(r)) { // адаптер перезагрузился и потерял настройки INIT
                if (++errors >= MAX_ERRORS) return "$MAX_ERRORS errors in a row, last: adapter reset"
                initialize(ch)?.let { return if (running) it else null }
                if (!running) return null
                setState(ElmState.POLLING, null)
                continue
            }
            val kmh = r?.let { if (Elm327.isError(it)) null else Elm327.parseSpeed(it) }
            if (kmh != null) {
                errors = 0
                backoffIdx = 0
                emitSpeed(ObdSpeedEvent(t0 + rtt / 2, kmh, rtt))
            } else if (++errors >= MAX_ERRORS) {
                return "$MAX_ERRORS errors in a row, last: ${r?.let(::clean) ?: "timeout"}"
            }
            val pause = minIntervalMs - (nowMs() - t0)
            if (pause > 0) sleep(pause.toLong())
        }
        return null
    }

    /**
     * Сброс буфера, INIT и поиск протокола; возвращает причину неудачи или null (готово либо остановлено).
     * ATZ ждёт баннер ELM327, остальные AT-команды — OK; без него — drain и один повтор.
     */
    private fun initialize(ch: ElmChannel): String? {
        setState(ElmState.INIT, null)
        ch.drain()
        for (cmd in Elm327.INIT.dropLast(1)) {
            var attempt = 0
            while (true) {
                if (!running) return null
                ch.write(cmd)
                val r = ch.readUntilPrompt(timeoutFor(cmd))
                if (r != null && (if (cmd == "ATZ") hasBanner(r) else hasOk(r))) break
                if (++attempt >= 2) return "bad reply to $cmd: ${r?.let(::clean) ?: "timeout"}"
                ch.drain()
            }
        }
        val pids = Elm327.INIT.last()
        while (running) {
            setState(ElmState.SEARCHING, null)
            ch.write(pids)
            val r = ch.readUntilPrompt(timeoutFor(pids))
            if (r == null) {
                ch.drain()
                return "no response to $pids"
            }
            if (!running || hasPids(r)) return null
            if (Elm327.isReset(r)) return "adapter reset during $pids"
            ch.drain()
            setState(ElmState.NO_DATA, clean(r))
            sleep(NO_DATA_RETRY_MS)
        }
        return null
    }

    private fun closeChannel() {
        channel.getAndSet(null)?.let { runCatching { it.close() } }
    }

    private fun emitSpeed(e: ObdSpeedEvent) {
        try {
            onSpeed(e)
        } catch (t: Exception) {
            throw CallbackFailure(t)
        }
    }

    private fun setState(s: ElmState, msg: String?) {
        if (s == state) return
        state = s
        try {
            onState(s, msg)
        } catch (t: Exception) {
            throw CallbackFailure(t)
        }
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(2000, 5000, 10_000)
        const val RESPONSE_TIMEOUT_MS = 1000L
        const val NO_DATA_RETRY_MS = 5000L
        const val MAX_ERRORS = 5

        fun timeoutFor(cmd: String): Long = when (cmd) {
            "ATZ" -> 3000L
            "0100" -> 10_000L
            else -> RESPONSE_TIMEOUT_MS
        }

        fun lines(r: String) = r.replace(">", "").split('\r', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

        fun hasBanner(r: String) = lines(r).any { it.contains("ELM327") }

        fun hasOk(r: String) = lines(r).any { it == "OK" }

        /** Ответ на 0100: строка с `4100` (возможно после SEARCHING...). */
        fun hasPids(r: String) = lines(r).any { it.replace(" ", "").contains("4100") }

        fun clean(r: String) = r.replace(">", "").split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")
    }
}
