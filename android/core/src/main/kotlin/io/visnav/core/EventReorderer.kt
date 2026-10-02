package io.visnav.core

import java.util.PriorityQueue

/** Элемент очереди: событие датчика или кадр (время кадра в мс, дескриптор может отсутствовать). */
sealed interface ReorderItem {
    val tMs: Double
    data class Sensor(val event: SensorEvent) : ReorderItem { override val tMs: Double get() = event.tMs }
    class Frame(val frameTMs: Long, val desc: FloatArray?) : ReorderItem { override val tMs: Double get() = frameTMs.toDouble() }
}

/**
 * Упорядочивает события по времени перед подачей в Localizer, как это делает replay. Элементы
 * выпускаются с задержкой [delayMs], чтобы отставшие потоки датчиков успели доставить свои события.
 * Опоздавший элемент (время меньше последнего выпущенного) выпускается при ближайшем drain в
 * порядке прибытия и учитывается в [late]. При переполнении ([maxQueued] элементов) отбрасываются
 * самые старые, счётчик — [dropped]. Порядок при равном времени: датчики, затем кадры, затем прибытие
 * (как стабильная сортировка `sensors + frames` в Replayer). push и drain потокобезопасны; sink вызывается вне замка.
 */
class EventReorderer(private val delayMs: Long = 1500, private val maxQueued: Int = 20_000) {
    private class Entry(val seq: Long, val item: ReorderItem) {
        val kind: Int get() = if (item is ReorderItem.Frame) 1 else 0
    }

    private val lock = Any()
    private val queue = PriorityQueue<Entry>(compareBy<Entry> { it.item.tMs }.thenBy { it.kind }.thenBy { it.seq })
    private val lateQueue = ArrayList<ReorderItem>()
    private var seq = 0L
    private var lastReleased = Double.NEGATIVE_INFINITY
    private var lateCount = 0
    private var droppedCount = 0

    val late: Int get() = synchronized(lock) { lateCount }
    val dropped: Int get() = synchronized(lock) { droppedCount }

    fun push(item: ReorderItem) = synchronized(lock) {
        if (item.tMs < lastReleased) { lateQueue.add(item); lateCount++ } else queue.add(Entry(seq++, item))
        while (queue.size + lateQueue.size > maxQueued) {
            if (queue.isNotEmpty()) queue.poll() else lateQueue.removeAt(0)
            droppedCount++
        }
    }

    fun drain(nowMs: Long, sink: (ReorderItem) -> Unit) {
        val limit = nowMs.toDouble() - delayMs
        release(take { it <= limit }, sink)
    }

    fun drainAll(sink: (ReorderItem) -> Unit) = release(take { true }, sink)

    /** Забирает под замком опоздавшие (в порядке прибытия) и затем готовые по времени элементы. */
    private fun take(ready: (Double) -> Boolean): List<ReorderItem> = synchronized(lock) {
        val out = ArrayList<ReorderItem>(lateQueue)
        lateQueue.clear()
        while (queue.isNotEmpty() && ready(queue.peek().item.tMs)) {
            val e = queue.poll()
            lastReleased = maxOf(lastReleased, e.item.tMs)
            out.add(e.item)
        }
        out
    }

    private fun release(items: List<ReorderItem>, sink: (ReorderItem) -> Unit) = items.forEach(sink)
}
