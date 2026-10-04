package io.visnav.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun assertValidNoNaN(json: String) {
        assertTrue(!json.contains("NaN") && !json.contains("Infinity"), json)
        Json.parseToJsonElement(json)
    }

    private fun assertNullGeometry(json: String) {
        assertValidNoNaN(json)
        assertEquals(JsonNull, Json.parseToJsonElement(json).jsonObject["geometry"])
    }

    @Test fun nonFiniteOrNonPositiveInputsGiveNullGeometry() {
        assertNullGeometry(MapGeometry.circlePolygon(55.75, 37.6, Double.NaN, "#2E7D32"))
        assertNullGeometry(MapGeometry.circlePolygon(55.75, 37.6, -5.0, "#2E7D32"))
        assertNullGeometry(MapGeometry.circlePolygon(Double.POSITIVE_INFINITY, 37.6, 30.0, "#2E7D32"))
        assertNullGeometry(MapGeometry.headingArrow(55.75, 37.6, Double.NaN, 20.0, "#1565C0"))
        assertNullGeometry(MapGeometry.headingArrow(55.75, 37.6, 0.0, -1.0, "#1565C0"))
        assertNullGeometry(MapGeometry.point(Double.NaN, 37.6, "#000000"))
        val props = Json.parseToJsonElement(MapGeometry.circlePolygon(55.75, 37.6, Double.NaN, "#2E7D32")).jsonObject["properties"]!!
        assertEquals("#2E7D32", props.jsonObject["color"]!!.jsonPrimitive.content)
    }

    @Test fun nonFinitePointsAreSkipped() {
        val nan = doubleArrayOf(Double.NaN, 37.5)
        assertNullGeometry(MapGeometry.lineString(listOf(doubleArrayOf(55.7, 37.5), nan)))
        val line = MapGeometry.lineString(listOf(doubleArrayOf(55.7, 37.5), nan, doubleArrayOf(55.8, 37.6)))
        assertValidNoNaN(line)
        assertEquals(2, Json.parseToJsonElement(line).jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.size)
        val pts = MapGeometry.points(listOf(nan, doubleArrayOf(55.7, 37.5)))
        assertValidNoNaN(pts)
        assertEquals(1, Json.parseToJsonElement(pts).jsonObject["features"]!!.jsonArray.size)
    }

    @Test fun circleRejectsTooFewSegments() {
        assertFailsWith<IllegalArgumentException> { MapGeometry.circlePolygon(55.75, 37.6, 30.0, "#000000", n = 2) }
    }

    @Test fun largeCircle500m() {
        val r = ring(MapGeometry.circlePolygon(55.75, 37.6, 500.0, "#F9A825"))
        assertEquals(r.first(), r.last())
        for ((lon, lat) in r.dropLast(1)) assertEquals(500.0, Geo.haversineM(55.75, 37.6, lat, lon), 3.0)
    }

    @Test fun arrowBaseWidthAndSetback() {
        val l = 20.0
        val r = ring(MapGeometry.headingArrow(55.75, 37.6, Math.PI / 2, l, "#1565C0"))   // на восток
        val enu = Enu(55.75, 37.6)
        val base = r.dropLast(1).drop(1).map { enu.toEn(it.second, it.first) }
        assertEquals(2, base.size)
        for (b in base) assertEquals(-0.3 * l, b[0], 0.05)
        assertEquals(0.6 * l, Math.abs(base[0][1] - base[1][1]), 0.05)
    }
}
