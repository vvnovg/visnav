package io.visnav.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrajectoryFormatHeaderTest {
    @Test fun headerEscapesRefpackString() {
        val nasty = "a\"b\\c\nd\u0001e"
        val o = Json.parseToJsonElement(TrajectoryFormat.fusionHeader(123L, nasty)).jsonObject
        assertEquals("fusion", o["type"]!!.jsonPrimitive.content)
        assertEquals("true", o["monitor"]!!.jsonPrimitive.content)
        assertEquals("123", o["session_started_ms"]!!.jsonPrimitive.content)
        assertEquals(nasty, o["refpack_created_at"]!!.jsonPrimitive.content)
    }

    @Test fun rowCarriesRoadFields() {
        val base = LocalizerOutput(1L, 55.0, 37.0, 3.0, null, null, "off", false, NavMode.GNSS, GnssHealth.GOOD, emptySet())
        assertTrue(TrajectoryFormat.row(base, false, null).endsWith(
            ",\"way_id\":null,\"road_lat\":null,\"road_lon\":null,\"road_conf\":null,\"road_used\":null}"))
        val withRoad = base.copy(road = RoadInfo(42L, 55.1, 37.1, 0.95, true))
        assertTrue(TrajectoryFormat.row(withRoad, false, null).endsWith(
            ",\"way_id\":42,\"road_lat\":55.1,\"road_lon\":37.1,\"road_conf\":0.95,\"road_used\":true}"))
    }

    @Test fun fusionHeaderCarriesRoads() {
        assertTrue(TrajectoryFormat.fusionHeader(1L, "c", "r1").endsWith(",\"roads\":\"r1\"}"))
        assertTrue(TrajectoryFormat.fusionHeader(1L, "c").endsWith(",\"roads\":null}"))
    }

    @Test fun fusionHeaderCarriesReorderDelay() {
        val h = TrajectoryFormat.fusionHeader(1L, "c", "r1", reorderDelayMs = 500)
        assertTrue(h.contains("\"reorder_delay_ms\":500"))
        Json.parseToJsonElement(h)
        assertTrue(!TrajectoryFormat.fusionHeader(1L, "c", "r1").contains("reorder_delay_ms"))
    }
}
