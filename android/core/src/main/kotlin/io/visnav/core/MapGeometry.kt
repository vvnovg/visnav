package io.visnav.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.sin

/** GeoJSON-строки для источников карты. Координаты в GeoJSON — `[lon, lat]`. */
object MapGeometry {
    private fun lonLat(lat: Double, lon: Double): JsonArray = buildJsonArray {
        add(JsonPrimitive(lon)); add(JsonPrimitive(lat))
    }

    private fun feature(geometry: JsonObject?, color: String? = null): JsonObject = buildJsonObject {
        put("type", "Feature")
        put("properties", buildJsonObject { if (color != null) put("color", color) })
        put("geometry", geometry ?: JsonNull)
    }

    private fun fin(vararg v: Double) = v.all { it.isFinite() }

    private fun polygon(enu: Enu, en: List<DoubleArray>, color: String): String {
        val ring = buildJsonArray {
            for (p in en) { val ll = enu.toLatLon(p[0], p[1]); add(lonLat(ll[0], ll[1])) }
            val f = enu.toLatLon(en[0][0], en[0][1]); add(lonLat(f[0], f[1]))
        }
        return feature(buildJsonObject {
            put("type", "Polygon")
            put("coordinates", buildJsonArray { add(ring) })
        }, color).toString()
    }

    fun circlePolygon(lat: Double, lon: Double, radiusM: Double, color: String, n: Int = 48): String {
        require(n >= 3) { "n must be >= 3" }
        if (!fin(lat, lon, radiusM) || radiusM <= 0.0) return feature(null, color).toString()
        val enu = Enu(lat, lon)
        val en = (0 until n).map { k ->
            val a = 2.0 * Math.PI * k / n
            doubleArrayOf(radiusM * sin(a), radiusM * cos(a))
        }
        return polygon(enu, en, color)
    }

    fun headingArrow(lat: Double, lon: Double, psiRad: Double, lengthM: Double, color: String): String {
        if (!fin(lat, lon, psiRad, lengthM) || lengthM <= 0.0) return feature(null, color).toString()
        val enu = Enu(lat, lon)
        val fx = sin(psiRad); val fy = cos(psiRad)      // вперёд
        val nx = cos(psiRad); val ny = -sin(psiRad)     // нормаль
        val l = lengthM
        val en = listOf(
            doubleArrayOf(fx * l, fy * l),
            doubleArrayOf(-0.3 * l * fx + 0.3 * l * nx, -0.3 * l * fy + 0.3 * l * ny),
            doubleArrayOf(-0.3 * l * fx - 0.3 * l * nx, -0.3 * l * fy - 0.3 * l * ny),
        )
        return polygon(enu, en, color)
    }

    fun point(lat: Double, lon: Double, color: String): String =
        if (!fin(lat, lon)) feature(null, color).toString()
        else feature(buildJsonObject {
            put("type", "Point")
            put("coordinates", lonLat(lat, lon))
        }, color).toString()

    private fun finitePoints(latLon: List<DoubleArray>) = latLon.filter { it.size >= 2 && fin(it[0], it[1]) }

    fun lineString(latLon: List<DoubleArray>): String {
        val pts = finitePoints(latLon)
        if (pts.size < 2) return feature(null).toString()
        return feature(buildJsonObject {
            put("type", "LineString")
            put("coordinates", buildJsonArray { for (p in pts) add(lonLat(p[0], p[1])) })
        }).toString()
    }

    fun points(latLon: List<DoubleArray>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", buildJsonArray {
            for (p in finitePoints(latLon)) add(feature(buildJsonObject {
                put("type", "Point")
                put("coordinates", lonLat(p[0], p[1]))
            }))
        })
    }.toString()

    fun empty(): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", buildJsonArray { })
    }.toString()
}
