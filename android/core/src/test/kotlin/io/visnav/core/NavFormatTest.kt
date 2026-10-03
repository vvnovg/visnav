package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavFormatTest {
    private val enu = Enu(55.75, 37.60)

    @Test fun headerAndEvents() {
        assertEquals(
            "{\"type\":\"nav\",\"session_started_ms\":5,\"roads\":\"r1\",\"dest\":[55.7,37.6]," +
                "\"outages\":[[1,2]],\"jams\":[],\"spoofs\":[]}",
            NavFormat.header(5L, "r1", 55.7, 37.6, listOf(longArrayOf(1, 2)), emptyList(), emptyList()),
        )
        val p = NavFormat.event(NavEvent.Prompt(7L, 1, PromptStage.NEAR, "Через 80 м поверните направо", 81.0), enu)
        assertEquals("{\"t_ms\":7,\"ev\":\"prompt\",\"maneuver\":1,\"stage\":\"near\",\"dist_m\":81.0," +
            "\"text\":\"Через 80 м поверните направо\"}", p)
        assertEquals("{\"t_ms\":9,\"ev\":\"arrive\"}", NavFormat.event(NavEvent.Arrived(9L), enu))
        val route = Route(listOf(RouteStep(0, true)), listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(100.0, 0.0)),
            doubleArrayOf(0.0, 100.0), 18.0)
        val m = listOf(Maneuver(ManeuverType.DEPART, 0.0, 0.0, 0.0, "Улица"), Maneuver(ManeuverType.ARRIVE, 100.0, 100.0, 0.0, null))
        val line = NavFormat.event(NavEvent.RouteReady(3L, route, m, false), enu)
        assertTrue(line.startsWith("{\"t_ms\":3,\"ev\":\"route\",\"reroute\":false,\"length_m\":100.0,\"duration_s\":18.0,\"polyline\":[["))
        assertTrue(line.contains("{\"type\":\"depart\",\"at_m\":0.0,") && line.contains("\"street\":\"Улица\",\"exit\":0}"))
        assertTrue(line.contains("{\"type\":\"arrive\",\"at_m\":100.0,") && line.contains("\"street\":null,\"exit\":0}"))
    }
}
