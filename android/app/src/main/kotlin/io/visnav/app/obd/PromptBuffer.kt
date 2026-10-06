package io.visnav.app.obd

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Добавляет кусок вывода адаптера к [buf]. Если в буфере есть ">", вырезает и возвращает ответ до ">"
 * включительно (остаток после ">" остаётся в буфере); иначе null.
 */
internal fun appendUntilPrompt(buf: StringBuilder, chunk: String): String? {
    buf.append(chunk)
    val i = buf.indexOf(">")
    if (i < 0) return null
    val reply = buf.substring(0, i + 1)
    buf.delete(0, i + 1)
    return reply
}

/**
 * Вывод ELM327, разобранный на ответы по ">": поток чтения (SPP) или колбэк уведомлений (BLE) вызывает
 * [append], поток сессии — [take]. Потокобезопасен.
 */
internal class PromptBuffer {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val partial = StringBuilder()
    private val replies = ArrayDeque<String>()
    private var failure: IOException? = null

    fun append(chunk: String) = lock.withLock {
        var reply = appendUntilPrompt(partial, chunk)
        while (reply != null) {
            replies.addLast(reply)
            reply = appendUntilPrompt(partial, "")
        }
        arrived.signalAll()
    }

    /** Канал оборвался: уже пришедшие ответы ещё отдаются, затем [take] бросает [e]. */
    fun fail(e: IOException) = lock.withLock {
        if (failure == null) failure = e
        arrived.signalAll()
    }

    /** Следующий ответ до ">" или null по таймауту; 0 — без ожидания. Прерываем (InterruptedException). */
    fun take(timeoutMs: Long): String? = lock.withLock {
        var leftNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (replies.isEmpty() && failure == null && leftNs > 0) leftNs = arrived.awaitNanos(leftNs)
        replies.pollFirst() ?: failure?.let { throw IOException(it.message, it) }
    }

    /** Без ожидания отбрасывает пришедшие ответы и неполный хвост. */
    fun clear() = lock.withLock {
        replies.clear()
        partial.setLength(0)
    }

    /**
     * Отбрасывает уже пришедшее, затем ждёт запоздавший вывод: пока за [timeoutMs] что-то приходит (не больше
     * 5 раз), отбрасывает и его. В конце буфер пуст.
     */
    fun drain(timeoutMs: Long) {
        clear()
        repeat(5) {
            val more = lock.withLock {
                val before = partial.length
                if (replies.isEmpty() && failure == null) arrived.await(timeoutMs, TimeUnit.MILLISECONDS)
                val got = replies.isNotEmpty() || partial.length != before
                replies.clear()
                partial.setLength(0)
                got && failure == null
            }
            if (!more) return
        }
    }
}
