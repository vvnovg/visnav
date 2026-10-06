package io.visnav.replay

import io.visnav.core.GnssHealth
import io.visnav.core.GnssReason
import io.visnav.replay.SyntheticSession.T0
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Подмена со сползанием 1 м/с вдоль пути (на восток, по ходу движения) 700–770 с: положение — через --spoof
 * с рампой 70 м за 70 с, скорость в фиксах — +1 м/с (подменный сигнал согласован по доплеру).
 * Спидометр OBD: 37 км/ч при истинных 10 м/с (+3 %, как в начальной поправке). Сессия 13 мин: правило (b)
 * включается после 600 с обучения поправки. Эталонов на такой путь нет — визуальное выключено.
 */
class SpeedMismatchReplayTest {
    private fun dir(): File = kotlin.io.path.createTempDirectory("obd").toFile()

    private val start = T0 + 700_000
    private val drift = Spoof(start, T0 + 770_000, 70.0, 0.0, 70_000)
    private val durationS = 780

    private fun session(obd: Boolean) = SyntheticSession.session(
        dir(), obdKmh = if (obd) 37 else null, speedFromMs = 700_000, speedToMs = 770_000, speedBiasMps = 1f,
        durationS = durationS,
    )

    private fun run(s: SessionData, spoofs: List<Spoof>) =
        Replayer(SyntheticSession.pack(), ReplayConfig(visual = false)).run(s, emptyList(), spoofs = spoofs)

    @Test fun driftAlongPathWithObdRaisesSpeedMismatch() {
        val points = run(session(obd = true), listOf(drift))
        val first = points.firstOrNull { it.tMs >= start && GnssReason.SPEED_MISMATCH in it.reasons }
        assertNotNull(first, "SPEED_MISMATCH never raised")
        val after = first.tMs - start
        assertTrue(after <= 40_000, "SPEED_MISMATCH after $after ms")
        assertEquals(GnssHealth.UNTRUSTED, first.health)
        assertTrue(points.none { it.tMs < start && GnssReason.SPEED_MISMATCH in it.reasons })
    }

    @Test fun driftWithoutObdIsUnchanged() {
        val points = run(session(obd = false), listOf(drift))
        assertTrue(points.none { GnssReason.SPEED_MISMATCH in it.reasons })
    }

    @Test fun cleanSessionWithObdHasNoMismatch() {
        val points = run(SyntheticSession.session(dir(), obdKmh = 37, durationS = durationS), emptyList())
        val after = points.filter { it.tMs >= T0 + 10_000 }
        assertTrue(after.size > 100)
        assertTrue(after.none { GnssReason.SPEED_MISMATCH in it.reasons })
        assertEquals(0, after.count { it.health == GnssHealth.UNTRUSTED })
    }
}
