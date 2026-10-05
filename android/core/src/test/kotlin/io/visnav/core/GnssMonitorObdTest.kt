package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GnssMonitorObdTest {
    private fun fix(t: Double, speed: Double) = LocEvent(t, 55.75, 37.6, 4f, speed.toFloat(), 0.3f, 90f, 2f)
    private fun status(t: Double) = GnssStatusEvent(t, 30, 14, 35f, 5f, 44f, 6, 4, 3, 1)

    /** Фиксы раз в секунду на fromS..toS (включительно), OBD — за 100 мс до каждого фикса. */
    private fun GnssMonitor.drive(fromS: Int, toS: Int, vGps: Double, vObd: Double?) {
        for (s in fromS..toS) {
            val t = s * 1_000.0
            if (vObd != null) onObd(t - 100.0, vObd)
            onStatus(status(t))
            onFix(fix(t, vGps), 1.0, 2.0, 4.0)
        }
    }

    private fun GnssMonitor.mismatch(t: Double) = GnssReason.SPEED_MISMATCH in assess(t).reasons

    private val onlyInstant = MonitorConfig(distAbsM = 1e9)

    @Test fun instantMismatchNeedsFiveSeconds() {
        val six = GnssMonitor(onlyInstant); six.drive(0, 5, 15.0, 12.0) // 6 с расхождения 3 м/с
        assertTrue(six.mismatch(5_100.0))
        val four = GnssMonitor(onlyInstant); four.drive(0, 3, 15.0, 12.0) // 4 с
        assertFalse(four.mismatch(3_100.0))
        val five = GnssMonitor(onlyInstant); five.drive(0, 4, 15.0, 12.0) // 5 с, но 4 с от первого нарушения
        assertFalse(five.mismatch(4_100.0))
    }

    @Test fun instantThresholdIsRelativeAtHighSpeed() {
        val m = GnssMonitor(onlyInstant); m.drive(0, 10, 32.5, 30.0) // 2.5 < 0.1·30
        assertFalse(m.mismatch(10_100.0))
    }

    @Test fun distanceWindowCatchesSlowDrift() {
        val m = GnssMonitor(); m.armDistanceCheck()
        m.drive(0, 10, 16.0, 15.0) // 10 м за 10 с — меньше 15 м
        assertFalse(m.mismatch(10_100.0))
        assertFalse(m.obdSuspicious(10_100.0))
        m.drive(11, 30, 16.0, 15.0) // 1 м/с: правило (a) молчит, (b) — к 30 с
        assertTrue(m.mismatch(30_100.0))
        assertEquals(GnssHealth.UNTRUSTED, m.assess(30_100.0).health)
    }

    @Test fun distanceWindowIsOffUntilArmed() {
        val m = GnssMonitor()
        assertFalse(m.distanceCheckArmed)
        m.drive(0, 60, 16.0, 15.0) // 1 м/с: только (b) поймало бы
        assertFalse(m.mismatch(60_100.0))
        assertFalse(m.obdSuspicious(60_100.0))
        m.armDistanceCheck()
        m.drive(61, 62, 16.0, 15.0) // окно уже накоплено — нарушение сразу
        assertTrue(m.mismatch(62_100.0))
    }

    @Test fun matchingSpeedsStayClean() {
        val m = GnssMonitor(); m.armDistanceCheck(); m.drive(0, 120, 15.2, 15.0)
        assertEquals(Assessment(GnssHealth.GOOD, emptySet()), m.assess(120_100.0))
        assertFalse(m.obdSuspicious(120_100.0))
    }

    @Test fun standstillWithMovingGps() {
        val yes = GnssMonitor(); yes.drive(0, 3, 2.0, 0.0) // 3 с
        assertTrue(yes.mismatch(3_100.0))
        val no = GnssMonitor(); no.drive(0, 2, 2.0, 0.0) // 2 с
        assertFalse(no.mismatch(2_100.0))
        val slowGps = GnssMonitor(); slowGps.drive(0, 10, 1.4, 0.0) // GPS ≤ 1.5 — шум на стоянке
        assertFalse(slowGps.mismatch(10_100.0))
    }

    @Test fun staleObdDisablesChecks() {
        val m = GnssMonitor()
        m.onObd(0.0, 0.0)
        for (s in 2..60) { m.onStatus(status(s * 1_000.0)); m.onFix(fix(s * 1_000.0, 15.0), 1.0, 2.0, 4.0) }
        assertEquals(GnssHealth.GOOD, m.assess(60_100.0).health)
        // OBD каждые 1.5 с до фикса — старше 1 с: проверок нет.
        val old = GnssMonitor(onlyInstant)
        for (s in 0..20) {
            val t = s * 1_000.0
            old.onObd(t - 1_100.0, 5.0); old.onStatus(status(t)); old.onFix(fix(t, 15.0), 1.0, 2.0, 4.0)
        }
        assertFalse(old.mismatch(20_100.0))
        assertFalse(old.obdSuspicious(20_100.0))
    }

    @Test fun withoutObdNothingChanges() {
        val m = GnssMonitor(); m.drive(0, 60, 15.0, null)
        assertEquals(Assessment(GnssHealth.GOOD, emptySet()), m.assess(60_100.0))
        assertFalse(m.obdSuspicious(60_100.0))
    }

    @Test fun reasonHoldsTenSecondsAfterLastViolation() {
        val m = GnssMonitor(onlyInstant)
        m.drive(0, 7, 15.0, 12.0) // нарушения на 5, 6, 7 с
        m.drive(8, 30, 12.0, 12.0)
        assertTrue(m.mismatch(16_900.0))
        assertTrue(m.mismatch(17_000.0))
        assertFalse(m.mismatch(17_100.0))
        assertFalse(m.mismatch(30_100.0))
    }

    @Test fun reasonIsUntrusted() {
        val m = GnssMonitor(onlyInstant); m.drive(0, 5, 15.0, 12.0)
        val a = m.assess(5_100.0)
        assertEquals(GnssHealth.UNTRUSTED, a.health)
        assertEquals(setOf(GnssReason.SPEED_MISMATCH), a.reasons)
    }

    @Test fun suspiciousWhileAccumulatingAndHeld() {
        val m = GnssMonitor(onlyInstant)
        m.drive(0, 1, 15.0, 15.0)
        assertFalse(m.obdSuspicious(1_100.0))
        m.drive(2, 3, 15.0, 12.0) // (a) копит, причины ещё нет
        assertFalse(m.mismatch(3_100.0))
        assertTrue(m.obdSuspicious(3_100.0))
        m.drive(4, 4, 15.0, 15.0) // нарушение прервалось
        assertFalse(m.obdSuspicious(4_100.0))
        val c = GnssMonitor(); c.drive(0, 1, 2.0, 0.0) // (c) копит
        assertTrue(c.obdSuspicious(1_100.0))
        val held = GnssMonitor(onlyInstant)
        held.drive(0, 5, 15.0, 12.0); held.drive(6, 10, 12.0, 12.0)
        assertTrue(held.obdSuspicious(10_100.0)) // причина удерживается
        assertFalse(held.obdSuspicious(15_100.0))
    }
}
