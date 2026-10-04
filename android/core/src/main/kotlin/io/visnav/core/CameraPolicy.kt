package io.visnav.core

data class CameraTarget(val lat: Double, val lon: Double, val zoom: Double, val bearingDeg: Double, val follow: Boolean)

/** Политика камеры: масштаб по скорости, курс вверх при движении, режим «свободно» после жеста. */
class CameraPolicy(private val freeHoldMs: Long = 10_000) {
    private var freeSinceMs: Long? = null
    private var lastBearing = 0.0

    fun onUserGesture(tMs: Long) { freeSinceMs = tMs }

    fun recenter() { freeSinceMs = null }

    fun zoomFor(speedMps: Double): Double = when {
        speedMps < 8.0 -> 17.0
        speedMps < 15.0 -> 16.0
        speedMps < 25.0 -> 15.5
        else -> 15.0
    }

    fun update(tMs: Long, lat: Double, lon: Double, speedMps: Double, psiRad: Double): CameraTarget? {
        val since = freeSinceMs
        if (since != null) {
            if (tMs < since || tMs - since > freeHoldMs) freeSinceMs = null else return null
        }
        if (speedMps >= 3.0 && psiRad.isFinite()) {
            lastBearing = ((Math.toDegrees(psiRad) % 360.0) + 360.0) % 360.0
        }
        return CameraTarget(lat, lon, zoomFor(speedMps), lastBearing, follow = true)
    }
}
