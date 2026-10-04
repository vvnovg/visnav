package io.visnav.core

import kotlin.math.ceil

/**
 * Адаптивный интервал кадров по ступеням [stepsMs].
 * - `thermal ≥ 3` → самая медленная ступень;
 * - `thermal == 2`, `headroom ≥ headroomLimit` или p90 полного времени кадра > [p90LimitMs]
 *   → на ступень медленнее, не чаще раза в [slowCooldownMs] и не медленнее [softMaxMs];
 *   после замедления из-за p90 окно кадров очищается;
 * - [recoverAfterMs] без этих условий → на ступень быстрее;
 * - режим GNSS со здоровьем GOOD → не быстрее [gnssFloorMs]. Под полом своя ступень живёт
 *   по обычным правилам; когда условие снято, интервал сразу возвращается к ней.
 *
 * Первые [warmupFrames] замеров кадра (прогрев ORT) не учитываются.
 * `nowMs` — монотонные часы (`SystemClock.elapsedRealtime`); если время пошло назад,
 * отсчёты паузы и восстановления начинаются заново с `nowMs`.
 */
class FrameRateGovernor(
    val stepsMs: List<Long> = listOf(500, 750, 1000, 1500, 2000),
    private val slowCooldownMs: Long = 10_000, private val recoverAfterMs: Long = 60_000,
    private val p90LimitMs: Double = 250.0, private val headroomLimit: Double = 0.85,
    private val gnssFloorMs: Long = 1000, private val window: Int = 20,
    private val softMaxMs: Long = 1500, private val warmupFrames: Int = 5,
) {
    init {
        require(stepsMs.isNotEmpty() && stepsMs.zipWithNext().all { (a, b) -> a < b }) { "stepsMs must be ascending" }
        require(window > 0) { "window must be positive" }
        require(warmupFrames >= 0) { "warmupFrames must be non-negative" }
    }

    private val lastIdx = stepsMs.lastIndex
    private val floorIdx = stepsMs.indexOfFirst { it >= gnssFloorMs }.let { if (it < 0) lastIdx else it }
    private val softMaxIdx = stepsMs.indexOfLast { it <= softMaxMs }.coerceAtLeast(0)

    /** Ступень без пола GNSS. */
    private var step = 0
    private var gnssFloor = false
    private var lastSlowMs: Long? = null
    private var calmSinceMs: Long? = null
    private var framesSeen = 0
    private val costs = ArrayDeque<Double>()

    val intervalMs: Long
        @Synchronized get() = stepsMs[if (gnssFloor) maxOf(step, floorIdx) else step]

    /** pre + inf + search + fuse одного кадра. */
    @Synchronized
    fun onFrameCost(totalMs: Double) {
        if (framesSeen < warmupFrames) { framesSeen++; return }
        if (!totalMs.isFinite()) return
        costs.addLast(totalMs)
        while (costs.size > window) costs.removeFirst()
    }

    /** Вызывается раз в секунду или при новом sys-сэмпле; возвращает новый интервал. */
    @Synchronized
    fun update(nowMs: Long, thermal: Int?, headroom: Double?, mode: NavMode?, health: GnssHealth?): Long {
        lastSlowMs?.let { if (nowMs < it) lastSlowMs = nowMs }
        calmSinceMs?.let { if (nowMs < it) calmSinceMs = nowMs }

        val severe = thermal != null && thermal >= 3
        val slowFrames = p90() > p90LimitMs
        val hot = thermal == 2 || (headroom != null && headroom >= headroomLimit) || slowFrames
        when {
            severe -> {
                step = lastIdx; lastSlowMs = nowMs; calmSinceMs = nowMs
            }
            hot -> {
                val prev = lastSlowMs
                if (step < softMaxIdx && (prev == null || nowMs - prev >= slowCooldownMs)) {
                    step += 1; lastSlowMs = nowMs
                    if (slowFrames) costs.clear()
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
        gnssFloor = mode == NavMode.GNSS && health == GnssHealth.GOOD
        return intervalMs
    }

    /** p90 (nearest-rank) по полному окну; пока окно не заполнено — 0. */
    private fun p90(): Double {
        if (costs.size < window) return 0.0
        val sorted = costs.sorted()
        return sorted[ceil(0.9 * sorted.size).toInt() - 1]
    }
}
