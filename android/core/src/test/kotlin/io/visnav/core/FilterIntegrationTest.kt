package io.visnav.core

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

class FilterIntegrationTest {
    private val route = listOf(
        DriveSim.Segment(60.0, 12.0, 0.0),        // 60 с прямо — фильтр сходится по GNSS
        DriveSim.Segment(20.0, 12.0, 0.05),       // плавный поворот
        DriveSim.Segment(40.0, 12.0, 0.0),
        DriveSim.Segment(15.0, 12.0, -0.1),       // скорость постоянна: без одометрии её изменение в пропадании ненаблюдаемо
        DriveSim.Segment(45.0, 12.0, 0.0),
    )

    private data class Result(val errors: List<Double>, val finalError: Double, val distance: Double)

    /** GNSS доступен первые 60 с; затем пропадание до конца маршрута (120 с). */
    private fun run(useVisual: Boolean): Result {
        val samples = DriveSim(seed = 7).run(route)
        val ekf = Ekf2d()
        val yaw = YawRate().also { it.onAccel(0f, 0f, 9.81f) }
        val outageStart = 60.0
        var lastT = 0.0
        val errors = mutableListOf<Double>()
        var distance = 0.0
        var prev: DriveSim.Sample? = null
        for (s in samples) {
            val gnss = s.gnss
            if (!ekf.initialized) {
                if (gnss != null) ekf.init(gnss[0], gnss[1], gnss[3], gnss[2], 3.0, 0.05, 0.5)
                lastT = s.tS; prev = s
                continue
            }
            ekf.predict(s.tS - lastT, yaw.headingRate(0f, 0f, s.gyroZ.toFloat())!!)
            lastT = s.tS
            val inOutage = s.tS >= outageStart
            if (!inOutage && gnss != null) {
                ekf.updatePosition(gnss[0], gnss[1], 3.0)
                ekf.updateSpeed(gnss[2], 0.3)
                ekf.updateHeading(gnss[3], Math.toRadians(3.0))
            }
            val vis = s.vis
            if (inOutage && useVisual && vis != null) ekf.updatePosition(vis[0], vis[1], 8.0)
            if (inOutage) {
                distance += hypot(s.e - prev!!.e, s.n - prev.n)
                if (vis != null) errors.add(hypot(ekf.x[0] - s.e, ekf.x[1] - s.n))
            }
            prev = s
        }
        val last = samples.last()
        return Result(errors, hypot(ekf.x[0] - last.e, ekf.x[1] - last.n), distance)
    }

    private fun quantile(xs: List<Double>, q: Double) = xs.sorted()[((xs.size - 1) * q).toInt()]

    @Test fun deadReckoningDriftBelowThreePercent() { // NFR-5
        val r = run(useVisual = false)
        val driftPct = r.finalError / r.distance * 100
        assertTrue(driftPct <= 3.0, "drift $driftPct % over ${r.distance} m")
    }

    @Test fun visualFixesMeetNfr1() { // NFR-1
        val r = run(useVisual = true)
        val p50 = quantile(r.errors, 0.5); val p95 = quantile(r.errors, 0.95)
        assertTrue(p50 <= 5.0 && p95 <= 15.0, "P50=$p50 P95=$p95")
    }
}
