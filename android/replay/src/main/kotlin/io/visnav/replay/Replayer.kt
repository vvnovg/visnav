package io.visnav.replay

import io.visnav.core.FilterConfig
import io.visnav.core.FrameRecord
import io.visnav.core.GnssHealth
import io.visnav.core.GnssReason
import io.visnav.core.LocEvent
import io.visnav.core.Localizer
import io.visnav.core.LocalizerConfig
import io.visnav.core.NavMode
import io.visnav.core.RefPack
import io.visnav.core.SensorEvent

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

/** Прогон записанной сессии через общий [Localizer] с искусственными пропаданиями GPS. */
class Replayer(private val pack: RefPack, private val config: ReplayConfig) {
    fun run(session: SessionData, outages: List<Outage>): List<TrajPoint> {
        val localizer = Localizer(
            pack,
            LocalizerConfig(
                visual = config.visual, acceptSim = config.acceptSim, minRadiusM = config.minRadiusM,
                maxRadiusM = config.maxRadiusM, k = config.k, filter = config.filter, monitor = config.monitor,
            ),
        )
        fun inOutage(t: Double) = outages.any { it.contains(t) }
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
                        o.stationary, o.mode, o.health, o.reasons, null,
                    ))
                }
                is LocEvent -> if (!inOutage(t)) localizer.onSensor(ev)
                is SensorEvent -> localizer.onSensor(ev)
            }
        }
        return out
    }
}
