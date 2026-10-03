package io.visnav.replay

import io.visnav.core.LocalizerOutput
import io.visnav.core.TrajectoryFormat
import java.io.File
import kotlinx.serialization.json.JsonPrimitive

object TrajectoryWriter {
    fun write(file: File, session: SessionData, config: ReplayConfig, outages: List<Outage>,
        points: List<TrajPoint>, jams: List<Jam> = emptyList(), spoofs: List<Spoof> = emptyList(),
        roadsCreatedAt: String? = null,
    ) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            val outageJson = outages.joinToString(",") { "[${it.startMs},${it.endMs}]" }
            val jamJson = jams.joinToString(",") { "[${it.startMs},${it.endMs}]" }
            val spoofJson = spoofs.joinToString(",") { "[${it.startMs},${it.endMs},${it.eastM},${it.northM},${it.rampMs}]" }
            w.write("{\"type\":\"replay\",\"visual\":${config.visual},\"outages\":[$outageJson]," +
                "\"monitor\":${config.monitor},\"jams\":[$jamJson],\"spoofs\":[$spoofJson]," +
                "\"roads\":${roadsCreatedAt?.let { JsonPrimitive(it).toString() } ?: "null"}," +
                "\"road_constraint\":${config.roadConstraint}," +
                "\"session_started_ms\":${session.header.startedMs}," +
                "\"refpack_created_at\":${JsonPrimitive(session.header.refpackCreatedAt)}}")
            w.newLine()
            for (p in points) {
                val o = LocalizerOutput(
                    p.tMs, p.lat, p.lon, p.sigmaM, p.visSim, p.visAccepted, p.visState, p.stationary,
                    p.mode, p.health, p.reasons, p.road,
                )
                w.write(TrajectoryFormat.row(o, p.inOutage, p.injected))
                w.newLine()
            }
        }
    }
}
