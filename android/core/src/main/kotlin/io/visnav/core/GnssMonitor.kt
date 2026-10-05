package io.visnav.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

enum class GnssHealth { GOOD, DEGRADED, UNTRUSTED }
enum class GnssReason { FIX_GAP, NO_FIX, FEW_SATS, LOW_CN0, AGC_DROP, POOR_ACCURACY, JUMP, INNOVATION, UNIFORM_CN0, SPEED_MISMATCH }
data class Assessment(val health: GnssHealth, val reasons: Set<GnssReason>)

data class MonitorConfig(
    val fixGapMs: Double = 3_000.0,
    val noFixMs: Double = 10_000.0,
    val statusStaleMs: Double = 5_000.0,
    val minUsed: Int = 5,
    val minCn0: Float = 25f,            // дБ·Гц, средний по спутникам в решении
    val maxAccM: Float = 20f,           // Android: радиус 68 %
    val maxJumpMps: Double = 70.0,
    val jumpAccFactor: Double = 3.0,
    val jumpHoldMs: Double = 10_000.0,
    val innovationGate: Double = 13.8,  // χ², 2 ст. свободы, 99.9 %
    val innovationCount: Int = 3,
    val relockOkMs: Double = 10_000.0,
    val relockMaxSigmaM: Double = 30.0,
    val relockMaxDistM: Double = 30.0,
    val visualAgreeMs: Double = 2_000.0,
    val visualAgreeM: Double = 30.0,
    val uniformCn0StdDb: Float = 1.5f,  // у подменного сигнала все спутники почти одной силы
    val uniformMinUsed: Int = 6,
    val uniformWindow: Int = 5,
    val uniformMinCount: Int = 3,
    val agcDropDb: Float = 6f,
    val agcTauMs: Double = 20_000.0,
    val agcFreezeDb: Float = 1f,
    val agcRelearnMs: Double = 300_000.0,
    val obdFreshMs: Double = 1_000.0,       // OBD старше — проверки скорости не идут
    val mismatchAbsMps: Double = 2.0,       // (a) |v_gps − v_obd| > max(abs, rel·v_obd) подряд holdMs
    val mismatchRel: Double = 0.1,
    val mismatchHoldMs: Double = 5_000.0,
    val distWindowMs: Double = 30_000.0,    // (b) |Σv_gps·dt − Σv_obd·dt| за окно > max(abs, rel·Σv_obd·dt); см. armDistanceCheck
    val distAbsM: Double = 15.0,
    val distRel: Double = 0.05,
    val stillObdMps: Double = 0.3,          // (c) v_obd < stillObd и v_gps > stillGps подряд stillHoldMs
    val stillGpsMps: Double = 1.5,
    val stillHoldMs: Double = 3_000.0,
    val mismatchLatchMs: Double = 10_000.0, // SPEED_MISMATCH держится после последнего нарушения
)

private val UNTRUSTED_REASONS = setOf(GnssReason.NO_FIX, GnssReason.JUMP, GnssReason.INNOVATION, GnssReason.UNIFORM_CN0,
    GnssReason.SPEED_MISMATCH,
)

/**
 * Детектор деградации, глушения и подмены GNSS (FR-14). Время — мс настенных часов телефона.
 * Фиксация «расхождение с фильтром» снимается только по положительным доказательствам (визуальная
 * фиксация рядом с GNSS или точный фильтр рядом с GNSS), а не по таймауту: иначе правдоподобная
 * подмена со временем вернула бы систему в GOOD.
 */
class GnssMonitor(private val config: MonitorConfig = MonitorConfig()) {
    private data class VisualFix(val tMs: Double, val lat: Double, val lon: Double, val sigmaM: Double)

    private var lastFix: LocEvent? = null
    private var lastStatus: GnssStatusEvent? = null
    private val uniformFlags = ArrayDeque<Boolean>()
    private var lastAgc: AgcEvent? = null
    private var agcBaseline: Double? = null
    private var lastAgcT: Double? = null
    private var agcDropSince: Double? = null
    private var lastVisual: VisualFix? = null
    private var jumpUntil = Double.NEGATIVE_INFINITY
    private var badInnovations = 0
    private var latched = false
    private var okSince: Double? = null
    private var okNeededVisual = false
    private var reinit = false
    private var lastObdT = Double.NaN
    private var lastObdMps = Double.NaN
    private var speedCheckT = Double.NaN
    private var instantSince: Double? = null
    private var stillSince: Double? = null
    private val distWindow = ArrayDeque<DoubleArray>() // [t, v_gps·dt, v_obd·dt]
    private var lastViolationT = Double.NEGATIVE_INFINITY

    /** Включено ли правило (b); см. [armDistanceCheck]. */
    var distanceCheckArmed: Boolean = false
        private set

    fun onStatus(e: GnssStatusEvent) {
        val prev = lastStatus
        if (prev != null && e.tMs - prev.tMs > config.statusStaleMs) uniformFlags.clear()
        lastStatus = e
        val std = e.cn0Std
        uniformFlags.addLast(std != null && e.used >= config.uniformMinUsed && std < config.uniformCn0StdDb)
        while (uniformFlags.size > config.uniformWindow) uniformFlags.removeFirst()
    }

    fun onVisualFix(tMs: Double, lat: Double, lon: Double, sigmaM: Double) {
        lastVisual = VisualFix(tMs, lat, lon, sigmaM)
    }

    /** Скорость машины по OBD, уже умноженная на поправку спидометра. */
    fun onObd(tMs: Double, vObdMps: Double) {
        if (!vObdMps.isFinite()) return
        lastObdT = tMs; lastObdMps = vObdMps
    }

    /**
     * Идёт ли накопление нарушения (a) или (c), или удерживается SPEED_MISMATCH: поправку спидометра
     * в это время не учить. У (b) отдельного «накопления» нет: окно копит сумму всегда, нарушение — её превышение.
     */
    /**
     * Включить правило (b) — окно расстояний. Порог (b) — 5 % пройденного пути, а начальная поправка спидометра
     * (1/1.03) у честной машины может ошибаться сильнее (спидометр вправе завышать до ~10 %). Включённое сразу,
     * (b) срабатывало бы на честной езде, удерживало SPEED_MISMATCH и тем самым запрещало обучение поправки —
     * машина навсегда осталась бы UNTRUSTED. Поэтому (b) включает локализатор, когда kSlow сошлась (накоплено
     * достаточно времени обучения) или восстановлена из сохранения. До этого работают только (a) и (c).
     */
    fun armDistanceCheck() { distanceCheckArmed = true }

    fun obdSuspicious(tMs: Double): Boolean = instantSince != null || stillSince != null || mismatchHeld(tMs)

    fun onAgc(e: AgcEvent) {
        lastAgc = e
        if (!e.agcDb.isFinite()) return
        val prevT = lastAgcT
        lastAgcT = e.tMs
        val b = agcBaseline
        if (b == null) {
            if (reasonsWithoutAgc(e.tMs).isEmpty()) agcBaseline = e.agcDb.toDouble()
            return
        }
        if (e.agcDb < b - config.agcDropDb) {
            val since = agcDropSince ?: e.tMs.also { agcDropSince = it }
            if (e.tMs - since >= config.agcRelearnMs && statusReasons(e.tMs).isEmpty()) {
                agcBaseline = e.agcDb.toDouble(); agcDropSince = null
            }
            return
        }
        agcDropSince = null
        if (reasonsWithoutAgc(e.tMs).isEmpty() && e.agcDb >= b - config.agcFreezeDb) {
            val dt = if (prevT == null) 0.0 else max(0.0, e.tMs - prevT)
            val alpha = 1 - exp(-dt / config.agcTauMs)
            agcBaseline = b + alpha * (e.agcDb - b)
        }
    }

    fun onFix(e: LocEvent, innovationD2: Double?, distToFilterM: Double?, filterSigmaM: Double?) {
        val prev = lastFix
        if (prev != null && e.tMs <= prev.tMs) return
        if (prev != null) {
            val dt = (e.tMs - prev.tMs) / 1000.0
            val dist = Geo.haversineM(prev.lat, prev.lon, e.lat, e.lon)
            if (dist > config.maxJumpMps * dt + config.jumpAccFactor * (prev.accM + e.accM)) {
                jumpUntil = e.tMs + config.jumpHoldMs
            }
        }
        lastFix = e
        checkSpeed(e)
        if (!latched) {
            if (innovationD2 == null) { badInnovations = 0; return }
            val bad = innovationD2 > config.innovationGate &&
                distToFilterM != null && distToFilterM > config.relockMaxDistM
            badInnovations = if (bad) badInnovations + 1 else 0
            if (badInnovations >= config.innovationCount) {
                latched = true; okSince = null; okNeededVisual = false; reinit = false
            }
            return
        }
        val v = lastVisual
        val visualOk = v != null && abs(e.tMs - v.tMs) <= config.visualAgreeMs &&
            Geo.haversineM(e.lat, e.lon, v.lat, v.lon) <= max(config.visualAgreeM, 2 * v.sigmaM)
        val filterOk = filterSigmaM != null && filterSigmaM <= config.relockMaxSigmaM &&
            distToFilterM != null && distToFilterM <= config.relockMaxDistM
        if (!visualOk && !filterOk) { okSince = null; okNeededVisual = false; return }
        val since = okSince ?: e.tMs.also { okSince = it; okNeededVisual = false }
        if (!filterOk) okNeededVisual = true
        if (e.tMs - since >= config.relockOkMs) {
            latched = false; okSince = null; badInnovations = 0
            if (okNeededVisual) reinit = true
            okNeededVisual = false
        }
    }

    /** Сверка скорости GNSS с OBD (правила (a), (b), (c)); без свежего OBD — сброс накоплений. */
    private fun checkSpeed(e: LocEvent) {
        val vGps = e.speedMps?.toDouble()
        val t = e.tMs
        if (vGps == null || !vGps.isFinite() || lastObdT.isNaN() || abs(t - lastObdT) > config.obdFreshMs) {
            resetSpeedCheck(); return
        }
        val vObd = lastObdMps
        val prevT = speedCheckT
        if (!prevT.isNaN() && t - prevT > config.fixGapMs) resetSpeedCheck()
        val dt = if (speedCheckT.isNaN()) 0.0 else (t - speedCheckT) / 1000.0
        speedCheckT = t
        var violation = false

        if (abs(vGps - vObd) > max(config.mismatchAbsMps, config.mismatchRel * vObd)) {
            val since = instantSince ?: t.also { instantSince = it }
            if (t - since >= config.mismatchHoldMs) violation = true
        } else instantSince = null

        if (vObd < config.stillObdMps && vGps > config.stillGpsMps) {
            val since = stillSince ?: t.also { stillSince = it }
            if (t - since >= config.stillHoldMs) violation = true
        } else stillSince = null

        if (dt > 0) distWindow.addLast(doubleArrayOf(t, vGps * dt, vObd * dt))
        while (distWindow.isNotEmpty() && distWindow.first()[0] <= t - config.distWindowMs) distWindow.removeFirst()
        val gpsM = distWindow.sumOf { it[1] }
        val obdM = distWindow.sumOf { it[2] }
        if (distanceCheckArmed && abs(gpsM - obdM) > max(config.distAbsM, config.distRel * obdM)) violation = true

        if (violation) lastViolationT = t
    }

    private fun resetSpeedCheck() {
        speedCheckT = Double.NaN; instantSince = null; stillSince = null; distWindow.clear()
    }

    private fun mismatchHeld(tMs: Double) = tMs - lastViolationT <= config.mismatchLatchMs

    /** Есть ли отложенная переинициализация (не сбрасывает её). */
    fun hasPendingReinit(): Boolean = reinit

    /** true один раз после снятия фиксации по визуальному согласию: переинициализировать фильтр по GNSS. */
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
        val gap = if (fix == null) Double.POSITIVE_INFINITY else tMs - fix.tMs
        when {
            gap > config.noFixMs -> r += GnssReason.NO_FIX
            gap > config.fixGapMs -> r += GnssReason.FIX_GAP
            fix != null && fix.accM > config.maxAccM -> r += GnssReason.POOR_ACCURACY
        }
        r += statusReasons(tMs)
        if (tMs < jumpUntil) r += GnssReason.JUMP
        if (latched) r += GnssReason.INNOVATION
        if (mismatchHeld(tMs)) r += GnssReason.SPEED_MISMATCH
        return r
    }

    private fun statusReasons(tMs: Double): Set<GnssReason> {
        val s = lastStatus ?: return emptySet()
        if (tMs - s.tMs > config.statusStaleMs) return emptySet()
        val r = mutableSetOf<GnssReason>()
        if (s.used < config.minUsed) r += GnssReason.FEW_SATS
        val cn0 = s.cn0Mean
        if (cn0 != null && cn0 < config.minCn0) r += GnssReason.LOW_CN0
        if (uniformFlags.count { it } >= config.uniformMinCount) r += GnssReason.UNIFORM_CN0
        return r
    }

    private fun agcDropped(tMs: Double): Boolean {
        val a = lastAgc ?: return false
        val b = agcBaseline ?: return false
        if (tMs - a.tMs > config.statusStaleMs) return false
        return a.agcDb < b - config.agcDropDb
    }
}
