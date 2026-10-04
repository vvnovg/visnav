package io.visnav.core

import kotlinx.serialization.json.JsonPrimitive

/** Журнал ведения по маршруту (.nav.jsonl): заголовок и по строке на событие NavEvent. */
object NavFormat {
    private fun num(v: Double): String = if (v.isFinite()) v.toString() else "null"
    private fun str(s: String?): String = if (s == null) "null" else JsonPrimitive(s).toString()
    private fun windows(w: List<LongArray>) = w.joinToString(",") { "[${it[0]},${it[1]}]" }

    fun header(
        sessionStartedMs: Long, roadsCreatedAt: String?, destLat: Double, destLon: Double,
        outages: List<LongArray>, jams: List<LongArray>, spoofs: List<LongArray>,
    ): String = "{\"type\":\"nav\",\"session_started_ms\":$sessionStartedMs,\"roads\":${str(roadsCreatedAt)}," +
        "\"dest\":[${num(destLat)},${num(destLon)}],\"outages\":[${windows(outages)}],\"jams\":[${windows(jams)}]," +
        "\"spoofs\":[${windows(spoofs)}]}"

    fun event(ev: NavEvent, enu: Enu): String = when (ev) {
        is NavEvent.RouteReady -> {
            val poly = ev.route.points.joinToString(",") { val ll = enu.toLatLon(it[0], it[1]); "[${num(ll[0])},${num(ll[1])}]" }
            val ms = ev.maneuvers.joinToString(",") { m ->
                val ll = enu.toLatLon(m.e, m.n)
                "{\"type\":\"${m.type.name.lowercase()}\",\"at_m\":${num(m.atM)},\"lat\":${num(ll[0])}," +
                    "\"lon\":${num(ll[1])},\"street\":${str(m.street)},\"exit\":${m.exit}}"
            }
            "{\"t_ms\":${ev.tMs},\"ev\":\"route\",\"reroute\":${ev.reroute},\"length_m\":${num(ev.route.lengthM)}," +
                "\"duration_s\":${num(ev.route.durationS)},\"polyline\":[$poly],\"maneuvers\":[$ms]}"
        }
        is NavEvent.Prompt -> "{\"t_ms\":${ev.tMs},\"ev\":\"prompt\",\"maneuver\":${ev.maneuver},\"then\":${ev.thenManeuver ?: "null"}," +
            "\"stage\":\"${ev.stage.name.lowercase()}\",\"dist_m\":${num(ev.distM)},\"text\":${str(ev.text)}}"
        is NavEvent.Arrived -> "{\"t_ms\":${ev.tMs},\"ev\":\"arrive\"}"
        is NavEvent.RouteFailed -> "{\"t_ms\":${ev.tMs},\"ev\":\"route_failed\"}"
    }
}
