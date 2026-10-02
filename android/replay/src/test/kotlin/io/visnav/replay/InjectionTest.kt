package io.visnav.replay

import io.visnav.core.GnssHealth
import io.visnav.core.GnssReason
import io.visnav.core.NavMode
import io.visnav.replay.SyntheticSession.T0
import io.visnav.replay.SyntheticSession.truthErrorM
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InjectionTest {
    private fun dir(): File = kotlin.io.path.createTempDirectory("inj").toFile()

    private fun inWindow(points: List<TrajPoint>, fromS: Long, toS: Long) =
        points.filter { it.tMs >= T0 + fromS * 1000 && it.tMs < T0 + toS * 1000 }

    private fun p95(errors: List<Double>): Double = errors.sorted().let { it[it.size * 95 / 100] }

    @Test fun spoofIsDetectedAndIgnored() {
        val s = SyntheticSession.session(dir())
        val spoof = Spoof(T0 + 40_000, T0 + 70_000, 0.0, 200.0)
        val points = Replayer(SyntheticSession.pack(), ReplayConfig()).run(s, emptyList(), spoofs = listOf(spoof))
        val first = points.firstOrNull { it.tMs >= T0 + 40_000 && it.health == GnssHealth.UNTRUSTED }
        assertTrue(first != null && first.tMs - (T0 + 40_000) <= 5_000, "UNTRUSTED after ${first?.tMs?.minus(T0 + 40_000)}")
        val window = inWindow(points, 40, 70)
        val p95 = p95(window.map { truthErrorM(it) })
        assertTrue(p95 <= 15.0, "P95=$p95")
        // Контроль: ворота EKF сами отбрасывают скачок при точности фикса 3 м, а визуальные фиксации
        // возвращают трек, поэтому «монитор выключен» проверяем на фиксах с точностью 60 м без камеры.
        val loose = SyntheticSession.session(dir(), accM = 60f)
        // Фильтр втягивается медленно, поэтому в контроле подмена длится до 80-й секунды.
        val off = Replayer(SyntheticSession.pack(), ReplayConfig(monitor = false, visual = false))
            .run(loose, emptyList(), spoofs = listOf(spoof.copy(endMs = T0 + 80_000)))
        val endErr = truthErrorM(inWindow(off, 40, 80).last())
        assertTrue(endErr > 100.0, "monitor off end-of-window error=$endErr")
    }

    @Test fun jamLeavesGnssModeQuickly() {
        val s = SyntheticSession.session(dir())
        val jam = Jam(T0 + 40_000, T0 + 70_000)
        val points = Replayer(SyntheticSession.pack(), ReplayConfig()).run(s, emptyList(), jams = listOf(jam))
        val left = points.firstOrNull { it.tMs >= T0 + 40_000 && it.mode != NavMode.GNSS }
        assertTrue(left != null && left.tMs - (T0 + 40_000) <= 5_000, "left GNSS after ${left?.tMs?.minus(T0 + 40_000)}")
        val reasons = inWindow(points, 40, 70).flatMap { it.reasons }.toSet()
        assertTrue(GnssReason.FEW_SATS in reasons || GnssReason.NO_FIX in reasons, "reasons=$reasons")
        assertEquals("jam", inWindow(points, 45, 46).first().injected)
    }

    @Test fun cleanSessionHasNoFalseUntrusted() {
        val s = SyntheticSession.session(dir())
        val points = Replayer(SyntheticSession.pack(), ReplayConfig()).run(s, emptyList())
        val after = points.filter { it.tMs >= T0 + 10_000 }
        assertTrue(after.size > 100)
        assertEquals(0, after.count { it.health == GnssHealth.UNTRUSTED })
        assertTrue(points.all { it.injected == null })
    }

    @Test fun trajectoryHeaderAndRowsCarryInjection() {
        val s = SyntheticSession.session(dir())
        val jam = Jam(T0 + 10_000, T0 + 15_000)
        val spoof = Spoof(T0 + 40_000, T0 + 70_000, 0.0, 200.0, 5_000)
        val cfg = ReplayConfig()
        val points = Replayer(SyntheticSession.pack(), cfg).run(s, emptyList(), listOf(jam), listOf(spoof))
        val out = File(dir(), "t.jsonl")
        TrajectoryWriter.write(out, s, cfg, emptyList(), points, listOf(jam), listOf(spoof))
        val lines = out.readLines()
        assertTrue(
            lines[0].contains(
                "\"monitor\":true,\"jams\":[[${jam.startMs},${jam.endMs}]]," +
                    "\"spoofs\":[[${spoof.startMs},${spoof.endMs},0.0,200.0,5000]]",
            ),
            lines[0],
        )
        assertTrue(lines.drop(1).any { it.contains("\"t_ms\":${T0 + 50_000},") && it.contains("\"injected\":\"spoof\"") })
        assertTrue(lines.drop(1).any { it.contains("\"injected\":\"jam\"") })
    }

    @Test fun overlappingWindowsPreferSpoofOverJamOverOutage() {
        val s = SyntheticSession.session(dir())
        val points = Replayer(SyntheticSession.pack(), ReplayConfig()).run(
            s, listOf(Outage(T0 + 20_000, T0 + 60_000)), listOf(Jam(T0 + 30_000, T0 + 50_000)),
            listOf(Spoof(T0 + 40_000, T0 + 45_000, 0.0, 200.0)),
        )
        assertEquals("outage", inWindow(points, 25, 26).first().injected)
        assertEquals("jam", inWindow(points, 35, 36).first().injected)
        assertEquals("spoof", inWindow(points, 42, 43).first().injected)
        assertEquals("outage", inWindow(points, 55, 56).first().injected)
    }

    @Test fun rampedSpoofShiftGrowsLinearly() {
        val s = Spoof(1_000, 100_000, 0.0, 200.0, 30_000)
        assertEquals(0.0, s.fraction(1_000.0))
        assertEquals(0.5, s.fraction(16_000.0), 1e-9)
        assertEquals(1.0, s.fraction(60_000.0))
        assertEquals(1.0, Spoof(0, 10, 0.0, 1.0).fraction(5.0))
    }

    @Test fun dragOffSpoofWithUniformCn0IsNotFused() {
        val s = SyntheticSession.session(dir(), uniformFromMs = 40_000, uniformToMs = 80_000, uniformCn0Std = 1f)
        val spoof = Spoof(T0 + 40_000, T0 + 80_000, 0.0, 200.0, 30_000)
        val points = Replayer(SyntheticSession.pack(), ReplayConfig()).run(s, emptyList(), spoofs = listOf(spoof))
        val first = points.firstOrNull { it.tMs >= T0 + 40_000 && it.health == GnssHealth.UNTRUSTED }
        assertNotNull(first, "never UNTRUSTED")
        assertTrue(first.tMs - (T0 + 40_000) <= 5_000, "UNTRUSTED after ${first.tMs - (T0 + 40_000)}")
        assertTrue(GnssReason.UNIFORM_CN0 in first.reasons, "reasons=${first.reasons}")
        val p95 = p95(inWindow(points, 40, 80).map { truthErrorM(it) })
        assertTrue(p95 <= 15.0, "P95=$p95")
    }

    // Увод 200 м за 30 с (6.7 м/с) отсекают сами ворота EKF даже при выключенном мониторе, поэтому он
    // не доказывает защиту. Медленный увод (50 м за 60 с) ворота проходит; без камеры, чтобы она не
    // возвращала трек, это и закрепляет пропуск UNTRUSTED-фиксов в Localizer.onLoc.
    @Test fun slowDragOffThatPassesTheEkfGateIsNotFused() {
        val s = SyntheticSession.session(dir(), uniformFromMs = 40_000, uniformToMs = 110_000, uniformCn0Std = 1f)
        val spoof = Spoof(T0 + 40_000, T0 + 110_000, 0.0, 50.0, 60_000)
        val on = Replayer(SyntheticSession.pack(), ReplayConfig(visual = false)).run(s, emptyList(), spoofs = listOf(spoof))
        val onP95 = p95(inWindow(on, 40, 110).map { truthErrorM(it) })
        assertTrue(onP95 <= 15.0, "monitor on P95=$onP95")
        val off = Replayer(SyntheticSession.pack(), ReplayConfig(visual = false, monitor = false))
            .run(s, emptyList(), spoofs = listOf(spoof))
        val endErr = truthErrorM(inWindow(off, 40, 110).last())
        assertTrue(endErr > 30.0, "monitor off end-of-window error=$endErr")
    }

    // Измерение предела, не требование: увод без признаков в статусе приёмника.
    @Test fun dragOffSpoofWithoutCn0SignatureIsMeasured() {
        val s = SyntheticSession.session(dir())
        val spoof = Spoof(T0 + 40_000, T0 + 80_000, 0.0, 200.0, 30_000)
        val points = Replayer(SyntheticSession.pack(), ReplayConfig()).run(s, emptyList(), spoofs = listOf(spoof))
        val window = inWindow(points, 40, 80)
        val maxErr = window.maxOf { truthErrorM(it) }
        val firstUntrusted = points.firstOrNull { it.tMs >= T0 + 40_000 && it.health == GnssHealth.UNTRUSTED }
        val whenStr = firstUntrusted?.let { "${(it.tMs - (T0 + 40_000)) / 1000.0} s" } ?: "нет"
        System.err.println("drag-off without CN0 signature: max error in window = $maxErr m, first UNTRUSTED = $whenStr")
        assertTrue(window.isNotEmpty())
    }
}
