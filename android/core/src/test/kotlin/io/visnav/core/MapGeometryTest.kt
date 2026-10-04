package io.visnav.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapGeometryTest {
    private fun ring(feature: String) = Json.parseToJsonElement(feature).jsonObject["geometry"]!!.jsonObject["coordinates"]!!
        .jsonArray[0].jsonArray.map { val p = it.jsonArray; p[0].jsonPrimitive.double to p[1].jsonPrimitive.double }

    @Test fun circleIsClosedAndHasRadius() {
        val r = ring(MapGeometry.circlePolygon(55.75, 37.6, 30.0, "#2E7D32", n = 36))
        assertEquals(37, r.size); assertEquals(r.first(), r.last())
        for ((lon, lat) in r.dropLast(1)) assertEquals(30.0, Geo.haversineM(55.75, 37.6, lat, lon), 0.3)
        val props = Json.parseToJsonElement(MapGeometry.circlePolygon(55.75, 37.6, 30.0, "#2E7D32")).jsonObject["properties"]!!
        assertEquals("#2E7D32", props.jsonObject["color"]!!.jsonPrimitive.content)
    }

    @Test fun headingArrowPointsAlongPsi() {
        val r = ring(MapGeometry.headingArrow(55.75, 37.6, Math.PI / 2, 20.0, "#1565C0"))   // на восток
        val tip = r.maxBy { it.first }
        assertEquals(20.0, Geo.haversineM(55.75, 37.6, tip.second, tip.first), 0.3)
        assertEquals(55.75, tip.second, 1e-6)
        assertEquals(r.first(), r.last())
    }

    @Test fun lineAndPointsAreLonLat() {
        val line = Json.parseToJsonElement(MapGeometry.lineString(listOf(doubleArrayOf(55.7, 37.5), doubleArrayOf(55.8, 37.6))))
            .jsonObject["geometry"]!!.jsonObject
        assertEquals("LineString", line["type"]!!.jsonPrimitive.content)
        assertEquals(37.5, line["coordinates"]!!.jsonArray[0].jsonArray[0].jsonPrimitive.double)
        val pts = Json.parseToJsonElement(MapGeometry.points(listOf(doubleArrayOf(55.7, 37.5)))).jsonObject
        assertEquals("FeatureCollection", pts["type"]!!.jsonPrimitive.content)
        assertEquals(1, pts["features"]!!.jsonArray.size)
        assertTrue(MapGeometry.empty().contains("\"features\":[]"))
    }
}
