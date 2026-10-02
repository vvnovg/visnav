package io.visnav.core

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Синтетическая прямая: 10 м/с на восток 120 с, эталон каждые 10 м с дескриптором-«отпечатком»,
 * IMU 100 Гц с вибрацией, GNSS и статус 1 Гц, кадры 2 Гц.
 */
class LocalizerTest {
    private val enu = Enu(55.75, 37.60)
    private val n = 120
    private val dim = n + 1
    private val t0 = 1_700_000_000_000L

    private fun oneHot(i: Int) = FloatArray(dim).also { it[i] = 1f }

    private fun pack(): RefPack {
        val count = n + 1
        val lats = DoubleArray(count); val lons = DoubleArray(count)
        val desc = ShortArray(count * dim)
        for (i in 0 until count) {
            val ll = enu.toLatLon(i * 10.0, 0.0); lats[i] = ll[0]; lons[i] = ll[1]
            desc[i * dim + i] = Half.fromFloat(1f)
        }
        return RefPack(count, dim, lats, lons, FloatArray(count), desc)
    }

    private fun truthErrorM(o: LocalizerOutput): Double {
        val ll = enu.toLatLon((o.tMs - t0) / 1000.0 * 10.0, 0.0)
        return Geo.haversineM(ll[0], ll[1], o.lat, o.lon)
    }

    /** LocEvent и статус не подаются в окне [gapStartMs, gapEndMs) (мс от начала). */
    private fun run(config: LocalizerConfig, gapStartMs: Long = -1, gapEndMs: Long = -1): List<LocalizerOutput> {
        val localizer = Localizer(pack(), config)
        val out = ArrayList<LocalizerOutput>()
        for (step in 0..12_000) {
            val tMs = t0 + step * 10L
            val t = tMs.toDouble()
            val inGap = tMs - t0 >= gapStartMs && tMs - t0 < gapEndMs
            localizer.onSensor(AccelEvent(t, 0f, 0f, 9.81f + 0.3f * sin(step.toFloat())))
            localizer.onSensor(GyroEvent(t, 0f, 0f, 0f))
            val eMeters = step * 0.1
            if (step % 100 == 0 && !inGap) {
                val ll = enu.toLatLon(eMeters, 0.0)
                localizer.onSensor(LocEvent(t, ll[0], ll[1], 3f, 10f, 0.3f, 90f, 2f))
                localizer.onSensor(GnssStatusEvent(t, 16, 14, 35f, 5f))
            }
            if (step % 50 == 0) {
                localizer.onFrame(tMs, oneHot(minOf(n, (eMeters / 10.0).toInt())))?.let { out.add(it) }
            }
        }
        return out
    }

    @Test fun cleanDriveStaysInGnssMode() {
        val outputs = run(LocalizerConfig())
        assertTrue(outputs.size > 100)
        assertTrue(outputs.all { it.mode == NavMode.GNSS }, "modes: ${outputs.map { it.mode }.distinct()}")
        assertTrue(outputs.all { it.health == GnssHealth.GOOD }, "health: ${outputs.map { it.health }.distinct()}")
    }

    @Test fun gnssDropSwitchesToVisualWithinFiveSeconds() {
        // Тайминги монитора ужаты (по умолчанию NO_FIX наступает через 10 с, а VISUAL — ещё через 2 с
        // гистерезиса), чтобы проверить ровно проводку Localizer: GNSS -> VISUAL за ≤ 5 с.
        val config = LocalizerConfig(monitorConfig = MonitorConfig(fixGapMs = 800.0, noFixMs = 1_500.0))
        val outputs = run(config, gapStartMs = 30_000, gapEndMs = 60_000)
        val dropStart = t0 + 30_000; val dropEnd = t0 + 60_000

        val visual = outputs.firstOrNull { it.tMs >= dropStart && it.mode == NavMode.VISUAL }
        assertTrue(visual != null && visual.tMs - dropStart <= 5_000, "first VISUAL at ${visual?.tMs?.minus(dropStart)} ms")

        val errors = outputs.filter { it.tMs in dropStart until dropEnd }.map { truthErrorM(it) }.sorted()
        val p95 = errors[errors.size * 95 / 100]
        assertTrue(p95 <= 15.0, "P95=$p95")

        val back = outputs.firstOrNull { it.tMs >= dropEnd && it.mode == NavMode.GNSS }
        assertTrue(back != null && back.tMs - dropEnd <= 10_000, "back to GNSS at ${back?.tMs?.minus(dropEnd)} ms")
    }

    @Test fun monitorOffAlwaysUsesGnss() {
        val outputs = run(LocalizerConfig(monitor = false), gapStartMs = 30_000, gapEndMs = 60_000)
        assertTrue(outputs.size > 100)
        assertTrue(outputs.all { it.mode == NavMode.GNSS }, "modes: ${outputs.map { it.mode }.distinct()}")
    }
}
