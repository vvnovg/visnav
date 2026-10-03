package io.visnav.core

import kotlinx.serialization.json.JsonPrimitive

/** Строка траектории: поля M2a без изменений, в конце — mode, health, reasons, injected. */
object TrajectoryFormat {
    fun modeName(m: NavMode): String = when (m) {
        NavMode.GNSS -> "gnss"
        NavMode.FUSED -> "fused"
        NavMode.VISUAL -> "visual"
        NavMode.DEAD_RECKONING -> "dead_reckoning"
    }

    /** Заголовок журнала fusion; строка refpack_created_at экранируется как JSON. */
    fun fusionHeader(sessionStartedMs: Long, refpackCreatedAt: String, roadsCreatedAt: String? = null): String =
        "{\"type\":\"fusion\",\"monitor\":true,\"session_started_ms\":$sessionStartedMs," +
            "\"refpack_created_at\":${JsonPrimitive(refpackCreatedAt)},\"roads\":${str(roadsCreatedAt)}}"

    fun row(o: LocalizerOutput, inOutage: Boolean, injected: String?): String {
        val reasons = o.reasons.joinToString(",") { JsonPrimitive(it.name.lowercase()).toString() }
        val r = o.road
        return "{\"t_ms\":${o.tMs},\"lat\":${num(o.lat)},\"lon\":${num(o.lon)},\"sigma_m\":${num(o.sigmaM)}," +
            "\"outage\":$inOutage,\"vis_sim\":${num(o.visSim)},\"vis_ok\":${o.visAccepted}," +
            "\"vis_state\":${JsonPrimitive(o.visState)},\"stationary\":${o.stationary}," +
            "\"mode\":\"${modeName(o.mode)}\",\"health\":\"${o.health.name.lowercase()}\"," +
            "\"reasons\":[$reasons],\"injected\":${str(injected)}," +
            "\"way_id\":${r?.wayId ?: "null"},\"road_lat\":${num(r?.lat)},\"road_lon\":${num(r?.lon)}," +
            "\"road_conf\":${num(r?.confidence)},\"road_used\":${r?.used ?: "null"}}"
    }

    /** JSON has no NaN/Infinity literal; a non-finite value is written as `null` to keep every line valid JSON. */
    private fun num(v: Double?): String = if (v != null && v.isFinite()) v.toString() else "null"
    private fun num(v: Float?): String = if (v != null && v.isFinite()) v.toString() else "null"
    private fun str(s: String?): String = if (s == null) "null" else JsonPrimitive(s).toString()
}
