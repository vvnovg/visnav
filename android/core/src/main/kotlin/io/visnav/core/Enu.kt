package io.visnav.core

import kotlin.math.cos

/** Плоская система «восток–север» (м) вокруг опорной точки; для города погрешность плоскости мала. */
class Enu(val lat0: Double, val lon0: Double) {
    private val mPerDegLat = Math.PI / 180.0 * Geo.EARTH_RADIUS_M
    private val mPerDegLon = mPerDegLat * cos(Math.toRadians(lat0))

    fun toEn(lat: Double, lon: Double): DoubleArray =
        doubleArrayOf((lon - lon0) * mPerDegLon, (lat - lat0) * mPerDegLat)

    fun toLatLon(e: Double, n: Double): DoubleArray =
        doubleArrayOf(lat0 + n / mPerDegLat, lon0 + e / mPerDegLon)
}
