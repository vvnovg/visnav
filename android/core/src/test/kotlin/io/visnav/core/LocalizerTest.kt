package io.visnav.core

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * LocEvent и статус не подаются в окне [gapStartMs, gapEndMs) (мс от начала). Фиксы в окне
     * [spoofStartMs, spoofEndMs) сдвинуты на spoofNorthM м к северу (движение согласованное).
     * Кадры без дескриптора до visualFromMs.
     */
    private fun run(
        config: LocalizerConfig, gapStartMs: Long = -1, gapEndMs: Long = -1,
        spoofStartMs: Long = -1, spoofEndMs: Long = -1, spoofNorthM: Double = 0.0, visualFromMs: Long = 0,
    ): List<LocalizerOutput> {
        val localizer = Localizer(pack(), config)
        val out = ArrayList<LocalizerOutput>()
        for (step in 0..12_000) {
            val tMs = t0 + step * 10L
            val t = tMs.toDouble()
            val rel = tMs - t0
            val inGap = rel >= gapStartMs && rel < gapEndMs
            val spoof = if (rel >= spoofStartMs && rel < spoofEndMs) spoofNorthM else 0.0
            localizer.onSensor(AccelEvent(t, 0f, 0f, 9.81f + 0.3f * sin(step.toFloat())))
            localizer.onSensor(GyroEvent(t, 0f, 0f, 0f))
            val eMeters = step * 0.1
            if (step % 100 == 0 && !inGap) {
                val ll = enu.toLatLon(eMeters, spoof)
                localizer.onSensor(LocEvent(t, ll[0], ll[1], 3f, 10f, 0.3f, 90f, 2f))
                localizer.onSensor(GnssStatusEvent(t, 16, 14, 35f, 5f))
            }
            if (step % 50 == 0) {
                val d = if (rel >= visualFromMs) oneHot(minOf(n, (eMeters / 10.0).toInt())) else null
                localizer.onFrame(tMs, d)?.let { out.add(it) }
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

    private fun List<LocalizerOutput>.firstAfter(t: Long, pred: (LocalizerOutput) -> Boolean) =
        firstOrNull { it.tMs >= t && pred(it) }

    @Test fun gnssDropLeavesGnssWithinFiveSeconds() {
        // GnssStatusEvent в окне тоже не подаются; на оценку это не влияет (статус устаревает через 5 с
        // и тогда просто не даёт причин), деградацию определяет отсутствие фиксов.
        val outputs = run(LocalizerConfig(), gapStartMs = 30_000, gapEndMs = 60_000)
        val dropStart = t0 + 30_000; val dropEnd = t0 + 60_000
        val left = outputs.firstAfter(dropStart) { it.mode != NavMode.GNSS }
        val visual = outputs.firstAfter(dropStart) { it.mode == NavMode.VISUAL }
        val back = outputs.firstAfter(dropEnd) { it.mode == NavMode.GNSS }
        assertTrue(left != null && left.tMs - dropStart <= 5_000, "left GNSS at ${left?.tMs?.minus(dropStart)}")
        assertTrue(visual != null && visual.tMs - dropStart <= 12_500, "VISUAL at ${visual?.tMs?.minus(dropStart)}")
        val errors = outputs.filter { it.tMs in dropStart until dropEnd }.map { truthErrorM(it) }.sorted()
        val p95 = errors[errors.size * 95 / 100]
        assertTrue(p95 <= 15.0, "P95=$p95")
        assertTrue(back != null && back.tMs - dropEnd <= 10_000, "back to GNSS at ${back?.tMs?.minus(dropEnd)}")
    }

    @Test fun tightenedMonitorConfigReachesVisualQuickly() {
        val config = LocalizerConfig(monitorConfig = MonitorConfig(fixGapMs = 800.0, noFixMs = 1_500.0))
        val outputs = run(config, gapStartMs = 30_000, gapEndMs = 60_000)
        val visual = outputs.firstAfter(t0 + 30_000) { it.mode == NavMode.VISUAL }
        assertTrue(visual != null && visual.tMs - (t0 + 30_000) <= 5_000)
    }

    @Test fun gnssDropWithoutVisualLeavesGnssThenDeadReckoning() {
        val outputs = run(LocalizerConfig(visual = false), gapStartMs = 30_000, gapEndMs = 70_000)
        val dropStart = t0 + 30_000
        val left = outputs.firstAfter(dropStart) { it.mode != NavMode.GNSS }
        val dr = outputs.firstAfter(dropStart) { it.mode == NavMode.DEAD_RECKONING }
        assertTrue(left != null && left.tMs - dropStart <= 5_000, "left GNSS at ${left?.tMs?.minus(dropStart)}")
        assertTrue(dr != null && dr.tMs - dropStart <= 13_000, "DR at ${dr?.tMs?.minus(dropStart)}")
        assertTrue(dr!!.tMs - dropStart > 5_000)
    }

    @Test fun spoofedFixesAreNotFusedOnceUntrusted() {
        val outputs = run(LocalizerConfig(), spoofStartMs = 40_000, spoofEndMs = 80_000, spoofNorthM = 200.0)
        val window = outputs.filter { it.tMs >= t0 + 40_000 && it.tMs < t0 + 80_000 && it.health == GnssHealth.UNTRUSTED }
        assertTrue(window.size > 20, "untrusted frames: ${window.size}")
        val worst = window.maxOf { truthErrorM(it) }
        assertTrue(worst <= 15.0, "worst error while UNTRUSTED = $worst")
    }

    @Test fun relocksByVisualAgreementAndReinitializesFilter() {
        // relockMaxSigmaM ≈ 0: фильтр никогда не считается «точным», поэтому защёлку снимает только согласие
        // GNSS с камерой, и фильтр переинициализируется по фиксу (путь consumeReinit).
        val config = LocalizerConfig(monitorConfig = MonitorConfig(relockMaxSigmaM = 0.001))
        val outputs = run(config, spoofStartMs = 40_000, spoofEndMs = 60_000, spoofNorthM = 200.0)
        val late = outputs.filter { it.tMs >= t0 + 90_000 }
        assertTrue(late.isNotEmpty())
        val worst = late.maxOf { truthErrorM(it) }
        assertTrue(worst <= 15.0, "late error=$worst")
        assertTrue(late.all { it.mode == NavMode.GNSS && it.health == GnssHealth.GOOD }, "late: ${late.map { it.mode to it.health }.distinct()}")
    }

    @Test fun monitorOffAlwaysUsesGnss() {
        val outputs = run(LocalizerConfig(monitor = false), gapStartMs = 30_000, gapEndMs = 60_000)
        assertTrue(outputs.size > 100)
        assertTrue(outputs.all { it.mode == NavMode.GNSS }, "modes: ${outputs.map { it.mode }.distinct()}")
    }

    /**
     * Защита в Localizer.onLoc: фикс при UNTRUSTED не сливается, даже пока режим (гистерезис) ещё GNSS.
     * Два одинаковых локализатора; A одна получает в 12.5 с фикс со сдвигом 8 м на север (ниже ворот
     * EKF, защёлки инновации и JUMP). Без защиты A сдвинулась бы, с защитой совпадает с B.
     */
    @Test fun untrustedFixIsNotFusedWhileModeLagsBehind() {
        val config = LocalizerConfig(visual = false)
        val a = Localizer(pack(), config)
        val b = Localizer(pack(), config)
        fun both(e: SensorEvent) { a.onSensor(e); b.onSensor(e) }
        for (step in 0..1_200) {
            val t = (t0 + step * 10L).toDouble()
            both(AccelEvent(t, 0f, 0f, 9.81f + 0.3f * sin(step.toFloat())))
            both(GyroEvent(t, 0f, 0f, 0f))
            if (step % 100 == 0) {
                val sec = step / 100
                val ll = enu.toLatLon(step * 0.1, 0.0)
                both(LocEvent(t, ll[0], ll[1], 3f, 10f, 0.3f, 90f, 2f))
                both(if (sec in 10..12) GnssStatusEvent(t, 16, 10, 35f, 1f) else GnssStatusEvent(t, 16, 14, 35f, 5f))
            }
        }
        val tShift = t0 + 12_500L
        val ll = enu.toLatLon(125.0, 8.0)
        a.onSensor(LocEvent(tShift.toDouble(), ll[0], ll[1], 3f, 10f, 0.3f, 90f, 2f))
        assertEquals(NavMode.GNSS, a.mode, "precondition: mode still lags in GNSS")
        // Кадр в тот же момент: состояние и режим не меняются, но видно здоровье; B получает такой же кадр.
        val pre = a.onFrame(tShift, null)!!
        b.onFrame(tShift, null)
        assertEquals(GnssHealth.UNTRUSTED, pre.health, "precondition: A is UNTRUSTED")
        assertEquals(NavMode.GNSS, pre.mode, "precondition: A still in GNSS")
        val oa = a.onFrame(t0 + 13_000L, null)!!
        val ob = b.onFrame(t0 + 13_000L, null)!!
        val d = Geo.haversineM(oa.lat, oa.lon, ob.lat, ob.lon)
        assertTrue(d < 1e-3, "A and B positions differ by $d m")
    }
}
