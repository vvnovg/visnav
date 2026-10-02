package io.visnav.replay

import io.visnav.core.AgcEvent
import io.visnav.core.FilterConfig
import io.visnav.core.FrameRecord
import io.visnav.core.GnssHealth
import io.visnav.core.Geo
import io.visnav.core.GnssReason
import io.visnav.core.GnssStatusEvent
import io.visnav.core.LocEvent
import io.visnav.core.Localizer
import io.visnav.core.LocalizerConfig
import io.visnav.core.NavMode
import io.visnav.core.RefPack
import io.visnav.core.SensorEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

data class Outage(val startMs: Long, val endMs: Long) {
    fun contains(tMs: Double) = tMs >= startMs && tMs < endMs
}

/** Искусственное глушение GNSS: нет фиксов, статус без спутников, АРУ ниже на [AGC_JAM_DROP_DB]. */
data class Jam(val startMs: Long, val endMs: Long) {
    fun contains(tMs: Double) = tMs >= startMs && tMs < endMs
}

/** Подмена: фиксы смещены на (eastM, northM); смещение растёт линейно от 0 за первые [rampMs] окна. */
data class Spoof(
    val startMs: Long, val endMs: Long, val eastM: Double, val northM: Double, val rampMs: Long = 0,
) {
    fun contains(tMs: Double) = tMs >= startMs && tMs < endMs
    fun fraction(tMs: Double): Double = if (rampMs <= 0) 1.0 else min(1.0, (tMs - startMs) / rampMs)
}

internal const val AGC_JAM_DROP_DB = 15f

data class ReplayConfig(
    val visual: Boolean = true,
    val acceptSim: Float = 0.5f,
    val minRadiusM: Double = 100.0,
    val maxRadiusM: Double = 3000.0,
    val k: Int = 5,
    val filter: FilterConfig = FilterConfig(),
    val monitor: Boolean = true,
)

/** Состояние визуальной фиксации на кадре: "off" (визуальный режим выключен), "no_desc" (нет
 * дескриптора для кадра), "empty_window" (нет эталонов в окне поиска), "below" (лучшее сходство
 * ниже acceptSim), "gated" (обновление отклонено хи-квадрат-гейтом), "ok" (обновление принято). */
data class TrajPoint(
    val tMs: Long, val lat: Double, val lon: Double, val sigmaM: Double,
    val inOutage: Boolean, val visSim: Float?, val visAccepted: Boolean?,
    val visState: String, val stationary: Boolean,
    val mode: NavMode, val health: GnssHealth, val reasons: Set<GnssReason>,
    val injected: String? = null,
)

/** Прогон записанной сессии через общий [Localizer] с искусственными пропаданиями, глушением и подменой GNSS. */
class Replayer(private val pack: RefPack, private val config: ReplayConfig) {
    fun run(
        session: SessionData, outages: List<Outage>,
        jams: List<Jam> = emptyList(), spoofs: List<Spoof> = emptyList(),
    ): List<TrajPoint> {
        val localizer = Localizer(
            pack,
            LocalizerConfig(
                visual = config.visual, acceptSim = config.acceptSim, minRadiusM = config.minRadiusM,
                maxRadiusM = config.maxRadiusM, k = config.k, filter = config.filter, monitor = config.monitor,
            ),
        )
        fun inOutage(t: Double) = outages.any { it.contains(t) }
        fun injected(t: Double): String? = when {
            spoofs.any { it.contains(t) } -> "spoof"
            jams.any { it.contains(t) } -> "jam"
            inOutage(t) -> "outage"
            else -> null
        }
        val out = ArrayList<TrajPoint>()

        val timeline: List<Pair<Double, Any>> =
            (session.sensors.map { it.tMs to (it as Any) } + session.frames.map { it.tMs.toDouble() to (it as Any) })
                .sortedBy { it.first }

        for ((t, ev) in timeline) {
            when (ev) {
                is FrameRecord -> {
                    val di = session.descriptors.indexOf(ev.tMs)
                    val desc = if (di < 0) null else session.descriptors.descriptor(di)
                    val o = localizer.onFrame(ev.tMs, desc) ?: continue
                    out.add(TrajPoint(
                        o.tMs, o.lat, o.lon, o.sigmaM, inOutage(t), o.visSim, o.visAccepted, o.visState,
                        o.stationary, o.mode, o.health, o.reasons, injected(t),
                    ))
                }
                is LocEvent -> {
                    if (inOutage(t) || jams.any { it.contains(t) }) continue
                    val spoof = spoofs.firstOrNull { it.contains(t) }
                    localizer.onSensor(if (spoof == null) ev else shifted(ev, spoof))
                }
                is GnssStatusEvent ->
                    localizer.onSensor(if (jams.any { it.contains(t) }) jammed(ev) else ev)
                is AgcEvent ->
                    localizer.onSensor(if (jams.any { it.contains(t) }) ev.copy(agcDb = ev.agcDb - AGC_JAM_DROP_DB) else ev)
                is SensorEvent -> localizer.onSensor(ev)
            }
        }
        return out
    }

    private fun jammed(e: GnssStatusEvent) = e.copy(
        used = 0, cn0Mean = null, cn0Std = null, cn0Max = null,
        usedGps = e.usedGps?.let { 0 }, usedGlo = e.usedGlo?.let { 0 },
        usedGal = e.usedGal?.let { 0 }, usedBds = e.usedBds?.let { 0 },
    )

    private fun shifted(e: LocEvent, s: Spoof): LocEvent {
        val f = s.fraction(e.tMs)
        val mPerDeg = PI / 180.0 * Geo.EARTH_RADIUS_M
        val lat = e.lat + s.northM * f / mPerDeg
        val lon = e.lon + s.eastM * f / (mPerDeg * cos(Math.toRadians(lat)))
        return e.copy(lat = lat, lon = lon)
    }
}
