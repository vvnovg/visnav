package io.visnav.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

data class LocalizerConfig(
    val visual: Boolean = true,
    val acceptSim: Float = 0.5f,
    val minRadiusM: Double = 100.0,
    val maxRadiusM: Double = 3000.0,
    val k: Int = 5,
    val filter: FilterConfig = FilterConfig(),
    val monitor: Boolean = true,
    val monitorConfig: MonitorConfig = MonitorConfig(),
    val modeConfig: ModeConfig = ModeConfig(),
    val fusedSigmaScale: Double = 3.0,
    val roadConstraint: Boolean = true,
    val minRoadConfidence: Double = 0.9,
    val roadHeadingSigmaDeg: Double = 10.0,
    val gnssQuietMs: Double = 2000.0,
    val minRoadSpeedMps: Double = 2.0,
    val matchConfig: MatchConfig = MatchConfig(),
)

/**
 * Привязка к дороге на кадре: OSM way, точка на оси, уверенность. `used` — дорога ограничивает позицию:
 * поперечная подсказка принята либо пропущена только потому, что дисперсия уже на нижней границе.
 */
data class RoadInfo(val wayId: Long, val lat: Double, val lon: Double, val confidence: Double, val used: Boolean)

/** visState: "off", "no_desc", "empty_window", "below", "gated", "ok" (см. M2a). */
data class LocalizerOutput(
    val tMs: Long, val lat: Double, val lon: Double, val sigmaM: Double,
    val visSim: Float?, val visAccepted: Boolean?, val visState: String, val stationary: Boolean,
    val mode: NavMode, val health: GnssHealth, val reasons: Set<GnssReason>,
    val road: RoadInfo? = null,
)

private const val HEADING_RESET_FRAMES = 3
private const val MIN_BIAS_VAR_AFTER_RESET = 0.01 * 0.01
private const val MIN_POS_SIGMA_M = 3.0
private const val MIN_SPEED_SIGMA_MPS = 0.1
private const val MIN_HEADING_SIGMA_DEG = 1.0
private const val DEFAULT_HEADING_ACC_DEG_INIT = 10.0
private const val DEFAULT_HEADING_ACC_DEG_UPDATE = 5.0
private const val DEFAULT_SPEED_ACC_INIT = 1.0
private const val DEFAULT_SPEED_ACC_UPDATE = 0.5

/**
 * Сигма из поля GNSS-точности: `null` или не-конечное значение заменяется значением по умолчанию,
 * иначе — значение поля, ограниченное снизу `floor` (Ekf2d.update* требует sigma > 0 и конечную).
 */
private fun sigma(v: Float?, default: Double, floor: Double): Double =
    if (v != null && v.isFinite()) max(v.toDouble(), floor) else default

/**
 * Общий конвейер локализации (фильтр M2a + монитор GNSS + режимы FR-15). Время — мс настенных часов.
 * С `monitor = false` ведёт себя как Replayer из M2a: режим всегда GNSS.
 */
class Localizer(
    private val pack: RefPack,
    private val config: LocalizerConfig = LocalizerConfig(),
    private val roads: RoadPack? = null,
) {
    private val index = GeoIndex(pack)
    private val ekf = Ekf2d(config.filter)
    private val yaw = YawRate()
    private val stationary = StationaryDetector()
    private val monitor = GnssMonitor(config.monitorConfig)
    private val modes = ModeManager(config.modeConfig)
    private var enu: Enu? = null
    private var lastT = 0.0
    private var lastOmega = 0.0
    private var lastZupt = Double.NEGATIVE_INFINITY
    private var lastVisualOk: Double? = null
    private var lastHealth = GnssHealth.GOOD
    private var lastReasons: Set<GnssReason> = emptySet()
    private var matcher: MapMatcher? = null
    private var lastGnssFusedT = Double.NEGATIVE_INFINITY
    private var headingInconsistent = 0
    /** Диагностика: сколько раз курс восстанавливался по устойчивой невязке с курсом дороги. */
    internal var headingResets = 0
        private set

    val initialized: Boolean get() = ekf.initialized
    internal val headingSigmaRad: Double get() = sqrt(ekf.headingVariance())
    internal fun lateralSigmaAcross(theta: Double): Double = sqrt(ekf.lateralVariance(theta))
    val mode: NavMode get() = modes.mode

    private fun predictTo(t: Double) {
        if (ekf.initialized && t > lastT) { ekf.predict((t - lastT) / 1000.0, lastOmega); lastT = t }
    }

    private fun updateMode(t: Double) {
        val a = monitor.assess(t)
        lastHealth = a.health; lastReasons = a.reasons
        modes.update(t, a.health, lastVisualOk)
    }

    fun onSensor(e: SensorEvent) {
        val t = e.tMs
        when (e) {
            is GyroEvent -> {
                val omega = yaw.headingRate(e.x, e.y, e.z) ?: 0.0
                predictTo(t)
                lastOmega = omega
                stationary.onGyro(t, e.x, e.y, e.z)
            }
            is AccelEvent -> {
                yaw.onAccel(e.x, e.y, e.z)
                stationary.onAccel(t, e.x, e.y, e.z)
                if (ekf.initialized && t - lastZupt >= 100.0 && stationary.isStationary(t)) {
                    predictTo(t); ekf.updateSpeed(0.0, 0.05); lastZupt = t
                }
            }
            is LocEvent -> onLoc(e)
            is GnssStatusEvent -> monitor.onStatus(e)
            is AgcEvent -> monitor.onAgc(e)
            else -> Unit
        }
    }

    private fun onLoc(ev: LocEvent) {
        if (!ev.lat.isFinite() || !ev.lon.isFinite() || !ev.accM.isFinite()) return
        val t = ev.tMs
        val spd = ev.speedMps; val brg = ev.bearingDeg
        if (!ekf.initialized) {
            if (spd != null && spd >= 3f && brg != null) {
                enu = Enu(ev.lat, ev.lon)
                val posSigma = sigma(ev.accM, MIN_POS_SIGMA_M, MIN_POS_SIGMA_M)
                val psiSigma = Math.toRadians(sigma(ev.bearingAccDeg, DEFAULT_HEADING_ACC_DEG_INIT, MIN_HEADING_SIGMA_DEG))
                val vSigma = sigma(ev.speedAccMps, DEFAULT_SPEED_ACC_INIT, MIN_SPEED_SIGMA_MPS)
                ekf.init(0.0, 0.0, Math.toRadians(brg.toDouble()), spd.toDouble(), posSigma, psiSigma, vSigma)
                lastT = t
                matcher = roads?.let { MapMatcher(RoadIndex(it, enu!!), config.matchConfig) }
                lastGnssFusedT = t
                monitor.onFix(ev, null, null, null) // первый фикс — тоже «последний фикс» для монитора
            }
            return
        }
        predictTo(t)
        val en = enu!!.toEn(ev.lat, ev.lon)
        val posSigma = sigma(ev.accM, MIN_POS_SIGMA_M, MIN_POS_SIGMA_M)
        if (config.monitor) {
            monitor.onFix(
                ev, ekf.positionD2(en[0], en[1], posSigma),
                hypot(en[0] - ekf.x[0], en[1] - ekf.x[1]), ekf.posSigma(),
            )
        } else {
            monitor.onFix(ev, null, null, null)
        }
        // Переинициализируем только когда нет других причин UNTRUSTED (например UNIFORM_CN0 или JUMP):
        // иначе запрос остаётся отложенным до их снятия.
        if (monitor.hasPendingReinit() && monitor.assess(t).health != GnssHealth.UNTRUSTED && monitor.consumeReinit()) {
            val psi = if (brg != null && spd != null && spd >= 3f) Math.toRadians(brg.toDouble()) else ekf.x[2]
            val v = spd?.toDouble() ?: ekf.x[3]
            val psiSigma = Math.toRadians(sigma(ev.bearingAccDeg, DEFAULT_HEADING_ACC_DEG_UPDATE, MIN_HEADING_SIGMA_DEG))
            val vSigma = sigma(ev.speedAccMps, DEFAULT_SPEED_ACC_UPDATE, MIN_SPEED_SIGMA_MPS)
            ekf.init(en[0], en[1], psi, v, posSigma, psiSigma, vSigma)
            matcher?.reset()
            headingInconsistent = 0
            lastGnssFusedT = t
            return
        }
        if (config.monitor) updateMode(t)
        // Подозрительный GNSS не сливаем в фильтр, какой бы режим ни показывался: гистерезис нужен
        // для стабильности индикации, а не для доверия.
        if (config.monitor && lastHealth == GnssHealth.UNTRUSTED) return
        when (modes.mode) {
            NavMode.GNSS -> applyFix(ev, en, posSigma, 1.0)
            NavMode.FUSED -> applyFix(ev, en, posSigma, config.fusedSigmaScale)
            NavMode.VISUAL, NavMode.DEAD_RECKONING -> Unit
        }
    }

    private fun applyFix(ev: LocEvent, en: DoubleArray, posSigma: Double, scale: Double) {
        val spd = ev.speedMps; val brg = ev.bearingDeg
        if (ekf.updatePosition(en[0], en[1], posSigma * scale)) lastGnssFusedT = ev.tMs
        if (spd != null) {
            val spdSigma = sigma(ev.speedAccMps, DEFAULT_SPEED_ACC_UPDATE, MIN_SPEED_SIGMA_MPS)
            ekf.updateSpeed(spd.toDouble(), spdSigma * scale)
        }
        if (brg != null && spd != null && spd >= 3f) {
            val brgSigma = Math.toRadians(sigma(ev.bearingAccDeg, DEFAULT_HEADING_ACC_DEG_UPDATE, MIN_HEADING_SIGMA_DEG))
            ekf.updateHeading(Math.toRadians(brg.toDouble()), brgSigma * scale)
        }
    }

    /** null, пока фильтр не инициализирован. */
    fun onFrame(tMs: Long, desc: FloatArray?): LocalizerOutput? {
        if (!ekf.initialized) return null
        val t = tMs.toDouble()
        predictTo(t)
        var visSim: Float? = null
        var visOk: Boolean? = null
        val visState: String
        if (!config.visual) {
            visState = "off"
        } else if (desc == null) {
            visState = "no_desc"
        } else {
            val center = enu!!.toLatLon(ekf.x[0], ekf.x[1])
            val radius = (3 * ekf.posSigma()).coerceIn(config.minRadiusM, config.maxRadiusM)
            val best = index.search(desc, config.k, center[0], center[1], radius).firstOrNull()
            if (best == null) {
                visState = "empty_window"
            } else {
                visSim = best.sim
                if (best.sim < config.acceptSim) {
                    visOk = false
                    visState = "below"
                } else {
                    val lat = pack.lats[best.index]; val lon = pack.lons[best.index]
                    val en = enu!!.toEn(lat, lon)
                    val sigmaVis = if (best.sim >= 0.7f) 8.0 else 15.0
                    val accepted = ekf.updatePosition(en[0], en[1], sigmaVis)
                    visOk = accepted
                    visState = if (accepted) "ok" else "gated"
                    if (accepted) {
                        lastVisualOk = t
                        if (config.monitor) monitor.onVisualFix(t, lat, lon, sigmaVis)
                    }
                }
            }
        }
        val road = matcher?.let { stepRoad(it, t) }
        // Режим и состояние обновляются на каждом кадре, чтобы глушение без визуальных фиксаций
        // не оставляло режим GNSS навсегда, а VISUAL — не переходил в DEAD_RECKONING.
        if (config.monitor) updateMode(t)
        val ll = enu!!.toLatLon(ekf.x[0], ekf.x[1])
        return LocalizerOutput(
            tMs, ll[0], ll[1], ekf.posSigma(), visSim, visOk, visState, stationary.isStationary(t),
            modes.mode, lastHealth, lastReasons, road,
        )
    }

    /** Шаг привязки к дороге; подсказка фильтру — только когда GNSS давно не сливался и привязка уверена. */
    private fun stepRoad(m: MapMatcher, t: Double): RoadInfo? {
        val match = m.step(ekf.x[0], ekf.x[1], ekf.posSigma(), ekf.x[2], ekf.x[3]) ?: return null
        var used = false
        if (config.roadConstraint && t - lastGnssFusedT > config.gnssQuietMs &&
            match.confidence >= config.minRoadConfidence && match.fit && !match.nearJunction &&
            ekf.x[3] >= config.minRoadSpeedMps
        ) {
            val sigmaLat = RoadClass.lateralSigmaM(match.roadClass)
            // Нижняя граница дисперсии: дорога не делает фильтр увереннее половины своей сигмы, иначе
            // вернувшийся GNSS не проходит гейт и фильтр залипает на оси дороги.
            val floor = (sigmaLat / 2) * (sigmaLat / 2)
            val lat = ekf.lateralVariance(match.travelBearing)
            val atFloor = lat <= floor
            // Раздувание шума (R): шум раздувается так, чтобы апостериорная дисперсия не опускалась ниже границы за один шаг.
            val r = max(sigmaLat * sigmaLat, floor * lat / (lat - floor))
            used = atFloor || ekf.updateLateral(match.e, match.n, match.travelBearing, sqrt(r))
            val headingSigma = Math.toRadians(config.roadHeadingSigmaDeg)
            if (used && ekf.x[3] >= config.matchConfig.minHeadingSpeedMps) {
                // Фильтр может быть самоуверен по курсу (дрейф гироскопа не в модели). Если невязка с курсом дороги
                // выходит за 3σ несколько кадров подряд, дисперсии ψ и b_g поднимаются и курс подтягивается.
                // Кадр без прохождения условий (или медленнее порога курса) счётчик не меняет.
                val res = wrapAngle(match.travelBearing - ekf.x[2])
                val bound = 3 * sqrt(ekf.headingVariance() + headingSigma * headingSigma)
                if (abs(res) > bound) headingInconsistent++ else headingInconsistent = 0
                if (headingInconsistent >= HEADING_RESET_FRAMES) {
                    ekf.inflateHeading(res * res, MIN_BIAS_VAR_AFTER_RESET)
                    ekf.updateHeading(match.travelBearing, headingSigma)
                    headingInconsistent = 0
                    headingResets++
                } else if (ekf.headingVariance() > (headingSigma / 2) * (headingSigma / 2)) {
                    ekf.updateHeading(match.travelBearing, headingSigma)
                }
            }
        }
        val ll = enu!!.toLatLon(match.e, match.n)
        return RoadInfo(match.wayId, ll[0], ll[1], match.confidence, used)
    }
}
