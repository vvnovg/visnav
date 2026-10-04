package io.visnav.core

import kotlin.math.ceil

/**
 * Адаптивный интервал кадров по ступеням [stepsMs].
 * - `thermal ≥ 3` → самая медленная ступень;
 * - `thermal == 2`, `headroom ≥ headroomLimit` или p90 полного времени кадра > [p90LimitMs]
 *   → на ступень медленнее, не чаще раза в [slowCooldownMs];
 * - [recoverAfterMs] без этих условий → на ступень быстрее;
 * - режим GNSS со здоровьем GOOD → не быстрее [gnssFloorMs]; пока ограничение действует,
 *   часы восстановления стоят, поэтому после выхода из GNSS скачка нет.
 */
class FrameRateGovernor(
    val stepsMs: List<Long> = listOf(500, 750, 1000, 1500, 2000),
    private val slowCooldownMs: Long = 10_000, private val recoverAfterMs: Long = 60_000,
    private val p90LimitMs: Double = 250.0, private val headroomLimit: Double = 0.85,
    private val gnssFloorMs: Long = 1000, private val window: Int = 20,
) {
    init {
        require(stepsMs.isNotEmpty() && stepsMs.zipWithNext().all { (a, b) -> a < b }) { "stepsMs must be ascending" }
        require(window > 0) { "window must be positive" }
    }

    private var step = 0
    private var lastSlowMs: Long? = null
    private var calmSinceMs: Long? = null
    private val costs = ArrayDeque<Double>()

    val intervalMs: Long get() = stepsMs[step]

    /** pre + inf + search + fuse одного кадра. */
    fun onFrameCost(totalMs: Double) {
        if (!totalMs.isFinite()) return
        costs.addLast(totalMs)
        while (costs.size > window) costs.removeFirst()
    }

    /** Вызывается раз в секунду или при новом sys-сэмпле; возвращает новый интервал. */
    fun update(nowMs: Long, thermal: Int?, headroom: Double?, mode: NavMode?, health: GnssHealth?): Long {
        val last = stepsMs.lastIndex
        val severe = thermal != null && thermal >= 3
        val hot = thermal == 2 || (headroom != null && headroom >= headroomLimit) || p90() > p90LimitMs
        when {
            severe -> {
                step = last; lastSlowMs = nowMs; calmSinceMs = nowMs
            }
            hot -> {
                val prev = lastSlowMs
                if (prev == null || nowMs - prev >= slowCooldownMs) {
                    step = minOf(step + 1, last); lastSlowMs = nowMs
                }
                calmSinceMs = nowMs
            }
            else -> {
                val since = calmSinceMs ?: nowMs.also { calmSinceMs = it }
                if (step > 0 && nowMs - since >= recoverAfterMs) {
                    step -= 1; calmSinceMs = nowMs
                }
            }
        }
        if (mode == NavMode.GNSS && health == GnssHealth.GOOD) {
            val floor = stepsMs.indexOfFirst { it >= gnssFloorMs }.let { if (it < 0) last else it }
            if (step <= floor) {
                step = floor; calmSinceMs = nowMs
            }
        }
        return intervalMs
    }

    /** p90 (nearest-rank) по полному окну; пока окно не заполнено — 0. */
    private fun p90(): Double {
        if (costs.size < window) return 0.0
        val sorted = costs.sorted()
        return sorted[ceil(0.9 * sorted.size).toInt() - 1]
    }
}
