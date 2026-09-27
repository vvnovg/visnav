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
                w.write("{\"t_ms\":${p.tMs},\"lat\":${p.lat},\"lon\":${p.lon},\"sigma_m\":${p.sigmaM}," +
                    "\"outage\":${p.inOutage},\"vis_sim\":${p.visSim},\"vis_ok\":${p.visAccepted}}")
                w.newLine()
            }
        }
    }
}
