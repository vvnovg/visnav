package io.visnav.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
    /**
     * Удержание дорогой (без INNOVATION, с «выходом по GPS») действует, только если фикс не дальше этого от
     * фильтра: дальше дорога фильтр утянуть не может (MatchConfig.maxFitM), и такой фикс — подмена (как в M2c).
     */
    val roadEscapeMaxM: Double = 50.0,
    val matchConfig: MatchConfig = MatchConfig(),
    val obd: ObdConfig = ObdConfig(),
)

/**
 * Скорость OBD-II как измерение фильтра. Измерение v = k·kmh/3.6, σ = max(sigmaMinMps, 0.5/3.6 + 0.01·v) м/с.
 * Поправка спидометра k — EMA с постоянной kTauMs, ограничена [kMin, kMax]; обучается по фиксу GNSS только при
 * здоровье GOOD, скорости GNSS ≥ learnMinSpeedMps и ненулевом OBD не дальше learnMaxAgeMs от фикса. Наблюдение
 * v_gnss/v_obd вне [kMin − 0.05, kMax + 0.05] — выброс, k к нему не тянется. Медленная kSlow (τ = kSlowTauMs) учится
 * по тем же правилам — для сравнения скоростей при проверке подмены: подмена не утянет её за минуты.
 *
 * Не учитывается: задержка ЭБУ (время события — середина запроса; возможная настройка latencyMs — после полевых
 * данных) и задний ход (скорость OBD без знака: v ≥ 0).
 */
data class ObdConfig(
    val enabled: Boolean = true,
    val kInit: Double = 1 / 1.03,
    val kMin: Double = 0.9,
    val kMax: Double = 1.1,
    val kTauMs: Long = 60_000,
    val kSlowTauMs: Long = 1_200_000,
    val sigmaMinMps: Double = 0.3,
    val learnMinSpeedMps: Double = 5.0,
    val learnMaxAgeMs: Long = 500,
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
    val psiRad: Double = Double.NaN, val speedMps: Double = Double.NaN,
    /** Текущая поправка спидометра k; null, пока не было ни одного события OBD (или OBD выключено). */
    val obdK: Double? = null,
)

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

private const val ROAD_HOLD_MS = 5_000.0
private const val ROAD_REJECTS_TO_SUSPEND = 2
private val ESCAPE_BLOCKERS = setOf(GnssReason.UNIFORM_CN0, GnssReason.JUMP, GnssReason.AGC_DROP)
private const val ZUPT_SIGMA_MPS = 0.05
/** Шаг EMA поправки спидометра не длиннее интервала фиксов: после долгого перерыва одно наблюдение не заменяет k. */
private const val OBD_LEARN_MAX_DT_MS = 1_000.0
/** Запас за [kMin, kMax], за которым наблюдение поправки — выброс (ноль OBD, скачок скорости GNSS). */
private const val OBD_RATIO_MARGIN = 0.05

/**
 * Общий конвейер локализации (фильтр M2a + монитор GNSS + режимы FR-15). Время — мс настенных часов.
 * С `monitor = false` ведёт себя как Replayer из M2a: режим всегда GNSS.
 *
 * «Удержание дорогой» (road-held): с последнего кадра, где дорога ограничила позицию (`road.used`), прошло
 * не больше 5 с, и фикс не дальше `roadEscapeMaxM` (50 м) от фильтра. Дорога сжимает поперечную P, и если
 * истинной дороги нет в OSM, а рядом идёт параллельная, фильтр стоит на чужой дороге, и здоровый GNSS не
 * проходит χ²-гейт. Поэтому, по решению владельца:
 * - пока фильтр удержан дорогой, монитор не получает χ²-невязку (INNOVATION не считается): расхождение
 *   со сжатым дорогой фильтром не должно объявлять настоящий GNSS подменой;
 * - «выход по GPS»: фикс без UNIFORM_CN0, JUMP и AGC_DROP, который гейт отверг бы, считается отказом (в любом
 *   режиме, даже если фикс не применяется); после двух отказов подряд подсказка дорогой приостанавливается,
 *   а неопределённость позиции раздувается до квадрата расстояния до фикса. Приостановка снимается, когда
 *   GNSS-позиция принята фильтром, при переинициализации и когда фиксов нет дольше `gnssQuietMs`.
 * Фикс дальше 50 м обрабатывается как в M2c: INNOVATION считается, выхода нет. Принятый владельцем остаток:
 * подмена со сдвигом ≤ 50 м во время удержания дорогой не ловится по INNOVATION (её ловят только
 * UNIFORM_CN0, JUMP и AGC_DROP) и может увести фильтр.
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
    private var lastRoadUsedT = Double.NEGATIVE_INFINITY
    private var roadRejects = 0
    private var roadSuspended = false
    private var lastLocT = Double.NEGATIVE_INFINITY
    private var obdK = config.obd.kInit
    private var obdKSlowValue = config.obd.kInit
    private var obdSeen = false
    private var lastObdT = Double.NaN
    private var lastObdMps = Double.NaN
    /** Фикс, пригодный для обучения k (GOOD, скорость ≥ порога): ждёт события OBD после себя. */
    private var learnFixT = Double.NaN
    private var learnFixMps = Double.NaN
    private var lastLearnT = Double.NEGATIVE_INFINITY

    val initialized: Boolean get() = ekf.initialized
    internal val headingSigmaRad: Double get() = sqrt(ekf.headingVariance())
    internal fun lateralSigmaAcross(theta: Double): Double = sqrt(ekf.lateralVariance(theta))
    val mode: NavMode get() = modes.mode
    /** Медленная поправка спидометра (τ = kSlowTauMs) для проверки подмены GNSS; null, пока не было OBD. */
    internal val obdKSlow: Double? get() = if (obdSeen) obdKSlowValue else null

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
                    predictTo(t); ekf.updateSpeed(0.0, ZUPT_SIGMA_MPS); lastZupt = t
                }
            }
            is LocEvent -> onLoc(e)
            is GnssStatusEvent -> monitor.onStatus(e)
            is AgcEvent -> monitor.onAgc(e)
            is ObdSpeedEvent -> onObd(e)
            else -> Unit
        }
    }

    /**
     * Скорость OBD: сначала — обучение k по ждущему фиксу, затем измерение v = k·kmh/3.6 в фильтр.
     * kmh = 0: на стоянке (IMU в покое) — как ZUPT, в движении — не применяется (ELM327 отдаёт 0 при сбоях).
     */
    private fun onObd(e: ObdSpeedEvent) {
        val c = config.obd
        if (!c.enabled) return
        val t = e.tMs
        val raw = e.kmh / 3.6
        if (!learnFixT.isNaN() && t >= learnFixT) {
            learnK(t, learnFixT, learnFixMps, obdSpeedAt(learnFixT, t, raw))
            learnFixT = Double.NaN
        }
        obdSeen = true
        lastObdT = t; lastObdMps = raw
        // Монитору — с медленной kSlow: подмена не успевает утянуть сравнение за собой.
        monitor.onObd(t, obdKSlowValue * raw)
        if (!ekf.initialized) return
        if (e.kmh == 0) {
            if (stationary.isStationary(t)) { predictTo(t); ekf.updateSpeed(0.0, ZUPT_SIGMA_MPS) }
            return
        }
        val v = obdK * raw
        predictTo(t)
        ekf.updateSpeed(v, max(c.sigmaMinMps, 0.5 / 3.6 + 0.01 * v))
    }

    /**
     * Скорость OBD (без k) на момент фикса tFix по последнему событию (до фикса) и новому (t ≥ tFix):
     * оба не дальше learnMaxAgeMs — линейная интерполяция, иначе ближайшее из подходящих, иначе null.
     * Нулевое событие не считается свежим: ноль рядом с фиксом в движении — сбой, а не скорость.
     */
    private fun obdSpeedAt(tFix: Double, t: Double, raw: Double): Double? {
        val maxAge = config.obd.learnMaxAgeMs.toDouble()
        val prevOk = !lastObdT.isNaN() && lastObdMps > 0.0 && lastObdT <= tFix && tFix - lastObdT <= maxAge
        val curOk = raw > 0.0 && t - tFix <= maxAge
        return when {
            prevOk && curOk -> if (t == lastObdT) raw else lastObdMps + (raw - lastObdMps) * (tFix - lastObdT) / (t - lastObdT)
            curOk -> raw
            prevOk -> lastObdMps
            else -> null
        }
    }

    /**
     * EMA k и kSlow по наблюдению vGnss/vObd с alpha = 1 − exp(−dt/τ), dt — от прошлого обучения (не больше 1 с).
     * Наблюдение вне [kMin − 0.05, kMax + 0.05] — выброс и отбрасывается. Пока монитор подозревает
     * расхождение скорости GNSS и OBD (копит нарушение или держит SPEED_MISMATCH), k и kSlow не учатся.
     */
    private fun learnK(tNow: Double, tFix: Double, vGnss: Double, vObd: Double?) {
        if (vObd == null || vObd <= 0.0 || tFix <= lastLearnT) return
        if (monitor.obdSuspicious(tNow)) return // идёт сверка с подозрением на подмену
        val c = config.obd
        val ratio = vGnss / vObd
        if (ratio < c.kMin - OBD_RATIO_MARGIN || ratio > c.kMax + OBD_RATIO_MARGIN) return
        val dt = min(tFix - lastLearnT, OBD_LEARN_MAX_DT_MS)
        obdK = (obdK + (1 - exp(-dt / c.kTauMs)) * (ratio - obdK)).coerceIn(c.kMin, c.kMax)
        obdKSlowValue = (obdKSlowValue + (1 - exp(-dt / c.kSlowTauMs)) * (ratio - obdKSlowValue)).coerceIn(c.kMin, c.kMax)
        lastLearnT = tFix
    }

    /**
     * Фикс для обучения k: здоровье GOOD по оценке монитора на момент фикса и скорость ≥ learnMinSpeedMps.
     * Прежний фикс, после которого OBD так и не пришло, учится по последнему OBD до него.
     */
    private fun noteFixForObd(ev: LocEvent) {
        val c = config.obd
        if (!c.enabled) return
        if (!learnFixT.isNaN()) {
            val fresh = !lastObdT.isNaN() && lastObdMps > 0.0 && abs(learnFixT - lastObdT) <= c.learnMaxAgeMs
            learnK(ev.tMs, learnFixT, learnFixMps, if (fresh) lastObdMps else null)
            learnFixT = Double.NaN
        }
        val spd = ev.speedMps ?: return
        if (spd < c.learnMinSpeedMps || monitor.assess(ev.tMs).health != GnssHealth.GOOD) return
        learnFixT = ev.tMs; learnFixMps = spd.toDouble()
    }

    private fun onLoc(ev: LocEvent) {
        if (!ev.lat.isFinite() || !ev.lon.isFinite() || !ev.accM.isFinite()) return
        val t = ev.tMs
        lastLocT = t
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
                noteFixForObd(ev)
            }
            return
        }
        predictTo(t)
        val en = enu!!.toEn(ev.lat, ev.lon)
        val posSigma = sigma(ev.accM, MIN_POS_SIGMA_M, MIN_POS_SIGMA_M)
        val d2 = ekf.positionD2(en[0], en[1], posSigma)
        val dist = hypot(en[0] - ekf.x[0], en[1] - ekf.x[1])
        val roadHeld = t - lastRoadUsedT <= ROAD_HOLD_MS && dist <= config.roadEscapeMaxM
        if (config.monitor) {
            // В удержании дорогой χ²-невязку не передаём: сжатая дорогой P не должна делать GNSS «подменой».
            monitor.onFix(ev, if (roadHeld) null else d2, dist, ekf.posSigma())
        } else {
            monitor.onFix(ev, null, null, null)
        }
        noteFixForObd(ev)
        if (roadHeld) checkRoadEscape(t, d2, dist)
        // Переинициализируем только когда нет других причин UNTRUSTED (например UNIFORM_CN0 или JUMP):
        // иначе запрос остаётся отложенным до их снятия.
        if (monitor.hasPendingReinit() && monitor.assess(t).health != GnssHealth.UNTRUSTED && monitor.consumeReinit()) {
            val psi = if (brg != null && spd != null && spd >= 3f) Math.toRadians(brg.toDouble()) else ekf.x[2]
            val v = spd?.toDouble() ?: ekf.x[3]
            val psiSigma = Math.toRadians(sigma(ev.bearingAccDeg, DEFAULT_HEADING_ACC_DEG_UPDATE, MIN_HEADING_SIGMA_DEG))
            val vSigma = sigma(ev.speedAccMps, DEFAULT_SPEED_ACC_UPDATE, MIN_SPEED_SIGMA_MPS)
            ekf.init(en[0], en[1], psi, v, posSigma, psiSigma, vSigma)
            matcher?.reset()
            lastGnssFusedT = t
            roadSuspended = false; roadRejects = 0
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

    /**
     * «Выход по GPS»: фикс без признаков подмены (UNIFORM_CN0, JUMP, AGC_DROP; INNOVATION не учитывается),
     * который χ²-гейт отверг бы, — отказ. Два отказа подряд: дорога приостанавливается, P позиции
     * раздувается до dist², чтобы следующий фикс прошёл гейт.
     */
    private fun checkRoadEscape(t: Double, d2: Double, dist: Double) {
        val clean = monitor.assess(t).reasons.none { it in ESCAPE_BLOCKERS }
        roadRejects = if (clean && d2 > config.filter.gateChi2Pos) roadRejects + 1 else 0
        if (roadRejects >= ROAD_REJECTS_TO_SUSPEND) {
            roadSuspended = true
            ekf.inflatePosition(dist * dist)
            roadRejects = 0
            lastRoadUsedT = Double.NEGATIVE_INFINITY // P раздута: дорога больше ничего не удерживает
        }
    }

    private fun applyFix(ev: LocEvent, en: DoubleArray, posSigma: Double, scale: Double) {
        val spd = ev.speedMps; val brg = ev.bearingDeg
        if (ekf.updatePosition(en[0], en[1], posSigma * scale)) { lastGnssFusedT = ev.tMs; roadSuspended = false }
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
        if (road?.used == true) lastRoadUsedT = t
        // Режим и состояние обновляются на каждом кадре, чтобы глушение без визуальных фиксаций
        // не оставляло режим GNSS навсегда, а VISUAL — не переходил в DEAD_RECKONING.
        if (config.monitor) updateMode(t)
        val ll = enu!!.toLatLon(ekf.x[0], ekf.x[1])
        return LocalizerOutput(
            tMs, ll[0], ll[1], ekf.posSigma(), visSim, visOk, visState, stationary.isStationary(t),
            modes.mode, lastHealth, lastReasons, road, ekf.x[2], ekf.x[3], if (obdSeen) obdK else null,
        )
    }

    /**
     * Шаг привязки к дороге; подсказка фильтру — только когда GNSS давно не сливался, привязка уверена и
     * дорога не приостановлена «выходом по GPS» (тогда привязка считается и сообщается, но `used` = false).
     * Приостановка снимается, если фиксов нет дольше gnssQuietMs.
     */
    private fun stepRoad(m: MapMatcher, t: Double): RoadInfo? {
        // GNSS снова нет: выходить не к чему, приостановка не должна залипнуть до следующего фикса.
        if (t - lastLocT > config.gnssQuietMs) { roadSuspended = false; roadRejects = 0 }
        val match = m.step(ekf.x[0], ekf.x[1], ekf.posSigma(), ekf.x[2], ekf.x[3]) ?: return null
        var used = false
        if (config.roadConstraint && !roadSuspended && t - lastGnssFusedT > config.gnssQuietMs &&
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
            if (used && ekf.x[3] >= config.matchConfig.minHeadingSpeedMps &&
                ekf.headingVariance() > (headingSigma / 2) * (headingSigma / 2)
            ) ekf.updateHeading(match.travelBearing, headingSigma)
        }
        val ll = enu!!.toLatLon(match.e, match.n)
        return RoadInfo(match.wayId, ll[0], ll[1], match.confidence, used)
    }
}
