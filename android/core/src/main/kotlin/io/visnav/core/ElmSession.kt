package io.visnav.core

/** Транспорт до адаптера ELM327 (Bluetooth Classic или BLE). */
interface ElmChannel {
    /** Отправляет команду, добавляя "\r". */
    fun write(cmd: String)

    /** Ответ до ">" включительно или null по таймауту. */
    fun readUntilPrompt(timeoutMs: Long): String?

    fun close()
}

enum class ElmState { CONNECTING, INIT, SEARCHING, POLLING, NO_DATA, ERROR, RECONNECT_WAIT, STOPPED }

/**
 * Диалог с ELM327: подключение, инициализация, опрос скорости (010D), переподключение.
 * Ошибка или таймаут опроса — счётчик; 5 подряд — закрытие канала и пауза 2/5/10/10… с.
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
    @Volatile private var channel: ElmChannel? = null
    private val lock = Any()
    private var runner: Thread? = null
    private var stopRequested = false
    private var backoffIdx = 0
    private var state: ElmState? = null

    /** Блокирующий цикл до stop(): подключение, INIT, опрос, переподключение. */
    fun run() {
        synchronized(lock) { runner = Thread.currentThread() }
        try {
            while (running) {
                val reason = try {
                    session()
                } catch (e: InterruptedException) {
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
        } finally {
            closeChannel()
            synchronized(lock) {
                runner = null
                if (stopRequested) Thread.interrupted() // снимает прерывание, выставленное stop()
            }
            setState(ElmState.STOPPED, null)
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
        channel = ch
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
            if (r != null && isReset(r)) { // адаптер перезагрузился и потерял настройки INIT
                initialize(ch)?.let { return if (running) it else null }
                if (!running) return null
                setState(ElmState.POLLING, null)
                continue
            }
            val kmh = r?.let { if (Elm327.isError(it)) null else Elm327.parseSpeed(it) }
            if (kmh != null) {
                errors = 0
                backoffIdx = 0
                onSpeed(ObdSpeedEvent(t0 + rtt / 2, kmh, rtt))
            } else if (++errors >= MAX_ERRORS) {
                return "$MAX_ERRORS errors in a row, last: ${r?.let(::clean) ?: "timeout"}"
            }
            val pause = minIntervalMs - (nowMs() - t0)
            if (pause > 0) sleep(pause.toLong())
        }
        return null
    }

    /** INIT и поиск протокола; возвращает причину неудачи или null (готово либо остановлено). */
    private fun initialize(ch: ElmChannel): String? {
        setState(ElmState.INIT, null)
        for (cmd in Elm327.INIT.dropLast(1)) {
            ch.write(cmd)
            ch.readUntilPrompt(timeoutFor(cmd)) ?: return "no response to $cmd"
        }
        val pids = Elm327.INIT.last()
        while (running) {
            setState(ElmState.SEARCHING, null)
            ch.write(pids)
            val r = ch.readUntilPrompt(timeoutFor(pids)) ?: return "no response to $pids"
            if (!running || PIDS_RE.containsMatchIn(r)) return null
            setState(ElmState.NO_DATA, clean(r))
            sleep(NO_DATA_RETRY_MS)
        }
        return null
    }

    private fun closeChannel() {
        channel?.let { ch ->
            channel = null
            runCatching { ch.close() }
        }
    }

    private fun setState(s: ElmState, msg: String?) {
        if (s == state) return
        state = s
        onState(s, msg)
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(2000, 5000, 10_000)
        const val RESPONSE_TIMEOUT_MS = 1000L
        const val NO_DATA_RETRY_MS = 5000L
        const val MAX_ERRORS = 5
        val PIDS_RE = Regex("41\\s*00", RegexOption.IGNORE_CASE)

        fun timeoutFor(cmd: String): Long = when (cmd) {
            "ATZ" -> 3000L
            "0100" -> 10_000L
            else -> RESPONSE_TIMEOUT_MS
        }

        // TODO: перейти на Elm327.isReset, когда он появится (ревью Task 1).
        fun isReset(r: String) = r.contains("ELM327") || r.contains("LV RESET")

        fun clean(r: String) = r.replace(">", "").split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")
    }
}
