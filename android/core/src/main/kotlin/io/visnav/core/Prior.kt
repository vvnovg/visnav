package io.visnav.core

import kotlin.math.max
import kotlin.math.min

enum class PriorMode { GPS, VISUAL }

data class GpsFix(val lat: Double, val lon: Double, val accM: Float, val tMs: Long)
data class Fix(val lat: Double, val lon: Double, val sim: Float, val tMs: Long)
data class Prior(val lat: Double, val lon: Double, val radiusM: Double)

/**
 * Окно поиска без фильтра (M1). GPS — как в режиме prior-500m бенчмарка M0; по нему считается
 * критерий M1. VISUAL имитирует пропажу GPS: после первой уверенной фиксации центр окна —
 * последняя принятая фиксация, радиус растёт с её возрастом (машина могла уехать).
 */
class PriorPolicy(
    val mode: PriorMode,
    val radiusM: Double = 500.0,
    val acceptSim: Float = 0.5f,
    val growthMps: Double = 30.0,
    val maxRadiusM: Double = 3000.0,
) {
    private var lastGps: GpsFix? = null
    private var lastAccepted: Fix? = null

    fun onGps(fix: GpsFix) { lastGps = fix }

    fun onVisualFix(fix: Fix) { if (fix.sim >= acceptSim) lastAccepted = fix }

    fun prior(nowMs: Long): Prior? {
        val gps = lastGps
        return when (mode) {
            PriorMode.GPS -> gps?.let { Prior(it.lat, it.lon, radiusM) }
            PriorMode.VISUAL -> {
                val fix = lastAccepted
                if (fix != null) {
                    val ageS = max(0L, nowMs - fix.tMs) / 1000.0
                    Prior(fix.lat, fix.lon, min(maxRadiusM, radiusM + growthMps * ageS))
                } else {
                    gps?.let { Prior(it.lat, it.lon, radiusM) }
                }
            }
        }
    }
}
