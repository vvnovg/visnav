package io.visnav.core

import java.util.PriorityQueue
import kotlin.math.ceil

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
 *
 * Опоздание элемента (`arrivalMs − tMs`) копится по источникам (frame, gnss_fix, gnss_status, imu,
 * agc, other) в окне до [LATENESS_WINDOW] значений; [latenessSnapshotAndReset] отдаёт статистику и
 * очищает окна.
 */
class EventReorderer(val delayMs: Long = 1500, private val maxQueued: Int = 20_000) {
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
    private val lateness = HashMap<String, ArrayDeque<Double>>()

    val late: Int get() = synchronized(lock) { lateCount }
    val dropped: Int get() = synchronized(lock) { droppedCount }

    /** Время прибытия неизвестно — опоздание считается равным 0. */
    fun push(item: ReorderItem) = push(item, 0.0)

    fun push(item: ReorderItem, arrivalMs: Long) = push(item, arrivalMs - item.tMs)

    private fun push(item: ReorderItem, latenessMs: Double) = synchronized(lock) {
        val window = lateness.getOrPut(sourceOf(item)) { ArrayDeque() }
        window.addLast(latenessMs)
        if (window.size > LATENESS_WINDOW) window.removeFirst()
        if (item.tMs < lastReleased) { lateQueue.add(item); lateCount++ } else queue.add(Entry(seq++, item))
        while (queue.size + lateQueue.size > maxQueued) {
            if (queue.isNotEmpty()) queue.poll() else lateQueue.removeAt(0)
            droppedCount++
        }
    }

    /**
     * Статистика опоздания по источникам с непустым окном, в порядке [SOURCES]; окна очищаются.
     * Под замком только копирование, сортировка и перцентили — вне замка.
     */
    fun latenessSnapshotAndReset(): Map<String, LatenessStats> {
        val windows = synchronized(lock) {
            val copy = lateness.mapValues { it.value.toDoubleArray() }
            lateness.clear()
            copy
        }
        val out = LinkedHashMap<String, LatenessStats>()
        for (src in SOURCES) {
            val sorted = windows[src]?.takeIf { it.isNotEmpty() }?.also { it.sort() } ?: continue
            fun pct(q: Double) = sorted[ceil(q * sorted.size).toInt() - 1]
            out[src] = LatenessStats(sorted.size, pct(0.5), pct(0.99), sorted.last())
        }
        return out
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

    private companion object {
        const val LATENESS_WINDOW = 5000
        val SOURCES = listOf("frame", "gnss_fix", "gnss_status", "imu", "agc", "other")

        fun sourceOf(item: ReorderItem): String = when (item) {
            is ReorderItem.Frame -> "frame"
            is ReorderItem.Sensor -> when (item.event) {
                is LocEvent -> "gnss_fix"
                is GnssStatusEvent -> "gnss_status"
                is GyroEvent, is AccelEvent, is GyroUncalEvent -> "imu"
                is AgcEvent -> "agc"
                is ClockEvent, is FrameCaptureEvent -> "other"
            }
        }
    }
}
