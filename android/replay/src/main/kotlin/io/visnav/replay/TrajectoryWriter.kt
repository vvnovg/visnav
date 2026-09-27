package io.visnav.replay

import java.io.File
import kotlinx.serialization.json.JsonPrimitive

object TrajectoryWriter {
    fun write(file: File, session: SessionData, config: ReplayConfig, outages: List<Outage>, points: List<TrajPoint>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            val outageJson = outages.joinToString(",") { "[${it.startMs},${it.endMs}]" }
            w.write("{\"type\":\"replay\",\"visual\":${config.visual},\"outages\":[$outageJson]," +
                "\"session_started_ms\":${session.header.startedMs}," +
                "\"refpack_created_at\":${JsonPrimitive(session.header.refpackCreatedAt)}}")
            w.newLine()
            for (p in points) {
                w.write("{\"t_ms\":${p.tMs},\"lat\":${num(p.lat)},\"lon\":${num(p.lon)},\"sigma_m\":${num(p.sigmaM)}," +
                    "\"outage\":${p.inOutage},\"vis_sim\":${num(p.visSim)},\"vis_ok\":${p.visAccepted}," +
                    "\"vis_state\":${JsonPrimitive(p.visState)},\"stationary\":${p.stationary}}")
                w.newLine()
            }
        }
    }

    /** JSON has no NaN/Infinity literal; a non-finite value is written as `null` to keep every line valid JSON. */
    private fun num(v: Double): String = if (v.isFinite()) v.toString() else "null"
    private fun num(v: Float?): String = if (v != null && v.isFinite()) v.toString() else "null"
}
