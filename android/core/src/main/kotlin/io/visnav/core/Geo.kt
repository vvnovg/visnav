package io.visnav.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Геодезия; радиус Земли совпадает с vpr_bench.geo, чтобы расстояния на телефоне и на ПК были одинаковыми. */
object Geo {
    const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val s1 = sin(dp / 2)
        val s2 = sin(dl / 2)
        val a = s1 * s1 + cos(p1) * cos(p2) * s2 * s2
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
