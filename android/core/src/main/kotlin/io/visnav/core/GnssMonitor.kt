package io.visnav.core

enum class GnssHealth { GOOD, DEGRADED, UNTRUSTED }
enum class GnssReason { NO_FIX, FEW_SATS, LOW_CN0, AGC_DROP, POOR_ACCURACY, JUMP, INNOVATION, UNIFORM_CN0 }
data class Assessment(val health: GnssHealth, val reasons: Set<GnssReason>)

data class MonitorConfig(
    val noFixMs: Double = 3_000.0,
    val statusStaleMs: Double = 5_000.0,
    val minUsed: Int = 5,
    val minCn0: Float = 25f,          // дБ·Гц, средний по спутникам в решении
    val maxAccM: Float = 20f,
    val agcDropDb: Float = 6f,
    val agcAlpha: Double = 0.05,
    val maxJumpMps: Double = 70.0,
    val jumpHoldMs: Double = 10_000.0,
    val innovationGate: Double = 13.8, // χ², 2 ст. свободы, 99.9 %
    val innovationCount: Int = 3,
    val relockOkMs: Double = 10_000.0,
    val reacquireMs: Double = 30_000.0,
    val uniformCn0StdDb: Float = 1.5f, // подменный сигнал у всех спутников почти одной силы
    val uniformMinUsed: Int = 6,
)

private val UNTRUSTED_REASONS = setOf(GnssReason.NO_FIX, GnssReason.JUMP, GnssReason.INNOVATION, GnssReason.UNIFORM_CN0)

/** Детектор деградации, глушения и подмены GNSS (FR-14). Время — мс настенных часов телефона. */
class GnssMonitor(private val config: MonitorConfig = MonitorConfig()) {
    private var lastFix: LocEvent? = null
    private var lastStatus: GnssStatusEvent? = null
    private var lastAgc: AgcEvent? = null
    private var agcBaseline: Double? = null
    private var jumpUntil = Double.NEGATIVE_INFINITY
    private var badInnovations = 0
    private var latchedSince: Double? = null
    private var okSince: Double? = null
    private var reinit = false

    fun onStatus(e: GnssStatusEvent) { lastStatus = e }

    fun onAgc(e: AgcEvent) {
        lastAgc = e
        if (!e.agcDb.isFinite()) return
        if (reasonsWithoutAgc(e.tMs).isEmpty()) {
            val b = agcBaseline
            agcBaseline = if (b == null) e.agcDb.toDouble() else b + config.agcAlpha * (e.agcDb - b)
        }
    }

    fun onFix(e: LocEvent, innovationD2: Double?) {
        val prev = lastFix
        if (prev != null && e.tMs > prev.tMs) {
            val speed = Geo.haversineM(prev.lat, prev.lon, e.lat, e.lon) / ((e.tMs - prev.tMs) / 1000.0)
            if (speed > config.maxJumpMps) jumpUntil = e.tMs + config.jumpHoldMs
        }
        lastFix = e
        if (innovationD2 == null) return
        val bad = !(innovationD2 <= config.innovationGate)
        val since = latchedSince
        if (since == null) {
            badInnovations = if (bad) badInnovations + 1 else 0
            if (badInnovations >= config.innovationCount) { latchedSince = e.tMs; okSince = null }
            return
        }
        if (bad) okSince = null else if (okSince == null) okSince = e.tMs
        val ok = okSince
        if (ok != null && e.tMs - ok >= config.relockOkMs) {
            unlatch()
        } else if (e.tMs - since >= config.reacquireMs && independentOk(e.tMs)) {
            unlatch(); reinit = true
        }
    }

    /** true один раз после повторного захвата: вызывающий должен переинициализировать фильтр по GNSS. */
    fun consumeReinit(): Boolean = reinit.also { reinit = false }

    fun assess(tMs: Double): Assessment {
        val r = reasonsWithoutAgc(tMs).toMutableSet()
        if (agcDropped(tMs)) r += GnssReason.AGC_DROP
        val health = when {
            r.any { it in UNTRUSTED_REASONS } -> GnssHealth.UNTRUSTED
            r.isNotEmpty() -> GnssHealth.DEGRADED
            else -> GnssHealth.GOOD
        }
        return Assessment(health, r)
    }

    private fun reasonsWithoutAgc(tMs: Double): Set<GnssReason> {
        val r = mutableSetOf<GnssReason>()
        val fix = lastFix
        if (fix == null || tMs - fix.tMs > config.noFixMs) r += GnssReason.NO_FIX
        else if (fix.accM > config.maxAccM) r += GnssReason.POOR_ACCURACY
        r += statusReasons(tMs)
        if (tMs < jumpUntil) r += GnssReason.JUMP
        if (latchedSince != null) r += GnssReason.INNOVATION
        return r
    }

    private fun statusReasons(tMs: Double): Set<GnssReason> {
        val s = lastStatus ?: return emptySet()
        if (tMs - s.tMs > config.statusStaleMs) return emptySet()
        val r = mutableSetOf<GnssReason>()
        if (s.used < config.minUsed) r += GnssReason.FEW_SATS
        val cn0 = s.cn0Mean
        if (cn0 != null && cn0 < config.minCn0) r += GnssReason.LOW_CN0
        val std = s.cn0Std
        if (std != null && s.used >= config.uniformMinUsed && std < config.uniformCn0StdDb) r += GnssReason.UNIFORM_CN0
        return r
    }

    private fun agcDropped(tMs: Double): Boolean {
        val a = lastAgc ?: return false
        val b = agcBaseline ?: return false
        if (tMs - a.tMs > config.statusStaleMs) return false
        return a.agcDb < b - config.agcDropDb
    }

    private fun independentOk(tMs: Double): Boolean {
        val fix = lastFix ?: return false
        return statusReasons(tMs).isEmpty() && !agcDropped(tMs) && tMs >= jumpUntil && fix.accM <= config.maxAccM
    }

    private fun unlatch() { latchedSince = null; okSince = null; badInnovations = 0 }
}
