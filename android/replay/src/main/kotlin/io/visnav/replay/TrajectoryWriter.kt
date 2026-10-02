package io.visnav.replay

import io.visnav.core.LocalizerOutput
import io.visnav.core.TrajectoryFormat
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
                val o = LocalizerOutput(
                    p.tMs, p.lat, p.lon, p.sigmaM, p.visSim, p.visAccepted, p.visState, p.stationary,
                    p.mode, p.health, p.reasons,
                )
                w.write(TrajectoryFormat.row(o, p.inOutage, p.injected))
                w.newLine()
            }
        }
    }
}
