package io.visnav.replay

import io.visnav.core.AccelEvent
import io.visnav.core.Ekf2d
import io.visnav.core.Enu
import io.visnav.core.FilterConfig
import io.visnav.core.GeoIndex
import io.visnav.core.GnssStatusEvent
import io.visnav.core.GyroEvent
import io.visnav.core.LocEvent
import io.visnav.core.RefPack
import io.visnav.core.StationaryDetector
import io.visnav.core.YawRate
import kotlin.math.max

data class Outage(val startMs: Long, val endMs: Long) {
    fun contains(tMs: Double) = tMs >= startMs && tMs < endMs
}

data class ReplayConfig(
    val visual: Boolean = true,
    val acceptSim: Float = 0.5f,
    val minRadiusM: Double = 100.0,
    val maxRadiusM: Double = 3000.0,
    val k: Int = 5,
    val filter: FilterConfig = FilterConfig(),
)

data class TrajPoint(
    val tMs: Long, val lat: Double, val lon: Double, val sigmaM: Double,
    val inOutage: Boolean, val visSim: Float?, val visAccepted: Boolean?,
)

private const val MIN_POS_SIGMA_M = 3.0
private const val MIN_SPEED_SIGMA_MPS = 0.1
private const val MIN_HEADING_SIGMA_DEG = 1.0
private const val DEFAULT_HEADING_ACC_DEG_INIT = 10.0
private const val DEFAULT_HEADING_ACC_DEG_UPDATE = 5.0
private const val DEFAULT_SPEED_ACC_INIT = 1.0
private const val DEFAULT_SPEED_ACC_UPDATE = 0.5

/**
 * Сигма из поля GNSS-точности: `null` или не-конечное значение заменяется значением по умолчанию
 * (уже не меньше `floor`), иначе — значение поля, ограниченное снизу `floor`. Некоторые устройства
 * сообщают точность GNSS равной 0 или (реже) NaN/Infinity — `Ekf2d.update*` требует `sigma > 0 &&
 * sigma.isFinite()`, поэтому все сигмы, выведенные из полей GNSS, проходят через эту функцию.
 */
private fun sigma(v: Float?, default: Double, floor: Double): Double =
    if (v != null && v.isFinite()) max(v.toDouble(), floor) else default

/**
 * Прогон записанной сессии через фильтр с искусственными пропаданиями GPS. Алгоритм — в плане M2a, Task 7.
 */
class Replayer(private val pack: RefPack, private val config: ReplayConfig) {
    private val index = GeoIndex(pack)

    fun run(session: SessionData, outages: List<Outage>): List<TrajPoint> {
        val ekf = Ekf2d(config.filter)
        val yaw = YawRate()
        val stationary = StationaryDetector()
        var enu: Enu? = null
        var lastT = 0.0
        var lastOmega = 0.0
        var lastZupt = Double.NEGATIVE_INFINITY
        val out = ArrayList<TrajPoint>()

        fun inOutage(t: Double) = outages.any { it.contains(t) }
        fun predictTo(t: Double) { if (ekf.initialized && t > lastT) { ekf.predict((t - lastT) / 1000.0, lastOmega); lastT = t } }

        val timeline: List<Pair<Double, Any>> =
            (session.sensors.map { it.tMs to (it as Any) } + session.frames.map { it.tMs.toDouble() to (it as Any) })
                .sortedBy { it.first }

        for ((t, ev) in timeline) {
            when (ev) {
                is GyroEvent -> {
                    val omega = yaw.headingRate(ev.x, ev.y, ev.z) ?: 0.0
                    predictTo(t)
                    lastOmega = omega
                    stationary.onGyro(t, ev.x, ev.y, ev.z)
                }
                is AccelEvent -> {
                    yaw.onAccel(ev.x, ev.y, ev.z)
                    stationary.onAccel(t, ev.x, ev.y, ev.z)
                    if (ekf.initialized && t - lastZupt >= 100.0 && stationary.isStationary(t)) {
                        predictTo(t); ekf.updateSpeed(0.0, 0.05); lastZupt = t
                    }
                }
                is LocEvent -> {
                    if (!ev.lat.isFinite() || !ev.lon.isFinite() || !ev.accM.isFinite()) continue
                    if (inOutage(t)) continue
                    val spd = ev.speedMps; val brg = ev.bearingDeg
                    if (!ekf.initialized) {
                        if (spd != null && spd >= 3f && brg != null) {
                            enu = Enu(ev.lat, ev.lon)
                            val posSigma = sigma(ev.accM, MIN_POS_SIGMA_M, MIN_POS_SIGMA_M)
                            val psiSigma = Math.toRadians(sigma(ev.bearingAccDeg, DEFAULT_HEADING_ACC_DEG_INIT, MIN_HEADING_SIGMA_DEG))
                            val vSigma = sigma(ev.speedAccMps, DEFAULT_SPEED_ACC_INIT, MIN_SPEED_SIGMA_MPS)
                            ekf.init(0.0, 0.0, Math.toRadians(brg.toDouble()), spd.toDouble(), posSigma, psiSigma, vSigma)
                            lastT = t
                        }
                        continue
                    }
                    predictTo(t)
                    val en = enu!!.toEn(ev.lat, ev.lon)
                    ekf.updatePosition(en[0], en[1], sigma(ev.accM, MIN_POS_SIGMA_M, MIN_POS_SIGMA_M))
                    if (spd != null) {
                        val spdSigma = sigma(ev.speedAccMps, DEFAULT_SPEED_ACC_UPDATE, MIN_SPEED_SIGMA_MPS)
                        ekf.updateSpeed(spd.toDouble(), spdSigma)
                    }
                    if (brg != null && spd != null && spd >= 3f) {
                        val brgSigma = Math.toRadians(sigma(ev.bearingAccDeg, DEFAULT_HEADING_ACC_DEG_UPDATE, MIN_HEADING_SIGMA_DEG))
                        ekf.updateHeading(Math.toRadians(brg.toDouble()), brgSigma)
                    }
                }
                is GnssStatusEvent -> Unit // используется в M2c
                is io.visnav.core.FrameRecord -> {
                    if (!ekf.initialized) continue
                    predictTo(t)
                    var visSim: Float? = null
                    var visOk: Boolean? = null
                    val di = session.descriptors.indexOf(ev.tMs)
                    if (config.visual && di >= 0) {
                        val center = enu!!.toLatLon(ekf.x[0], ekf.x[1])
                        val radius = (3 * ekf.posSigma()).coerceIn(config.minRadiusM, config.maxRadiusM)
                        val best = index.search(session.descriptors.descriptor(di), config.k, center[0], center[1], radius).firstOrNull()
                        if (best != null) {
                            visSim = best.sim
                            if (best.sim >= config.acceptSim) {
                                val en = enu!!.toEn(pack.lats[best.index], pack.lons[best.index])
                                visOk = ekf.updatePosition(en[0], en[1], if (best.sim >= 0.7f) 8.0 else 15.0)
                            } else {
                                visOk = false
                            }
                        }
                    }
                    val ll = enu!!.toLatLon(ekf.x[0], ekf.x[1])
                    out.add(TrajPoint(ev.tMs, ll[0], ll[1], ekf.posSigma(), inOutage(t), visSim, visOk))
                }
            }
        }
        return out
    }
}
