package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GnssMonitorTest {
    private fun fix(t: Double, lat: Double = 55.75, lon: Double = 37.6, acc: Float = 4f) =
        LocEvent(t, lat, lon, acc, 10f, 0.5f, 90f, 2f)
    private fun goodStatus(t: Double) = GnssStatusEvent(t, 30, 14, 35f, 5f, 44f, 6, 4, 3, 1)

    @Test fun cleanSignalIsGood() {
        val m = GnssMonitor()
        m.onStatus(goodStatus(0.0)); m.onFix(fix(0.0), 1.0)
        assertEquals(Assessment(GnssHealth.GOOD, emptySet()), m.assess(500.0))
    }

    @Test fun noFixAfterThreeSecondsIsUntrusted() {
        val m = GnssMonitor()
        m.onStatus(goodStatus(0.0)); m.onFix(fix(0.0), 1.0)
        assertEquals(GnssHealth.GOOD, m.assess(2_900.0).health)
        val a = m.assess(3_100.0)
        assertEquals(GnssHealth.UNTRUSTED, a.health); assertTrue(GnssReason.NO_FIX in a.reasons)
    }

    @Test fun fewSatellitesAndLowCn0Degrade() {
        val m = GnssMonitor()
        m.onFix(fix(0.0), 1.0)
        m.onStatus(GnssStatusEvent(0.0, 10, 3, 22f, 6f, 30f))
        val a = m.assess(100.0)
        assertEquals(GnssHealth.DEGRADED, a.health)
        assertEquals(setOf(GnssReason.FEW_SATS, GnssReason.LOW_CN0), a.reasons)
    }

    @Test fun staleStatusIsIgnored() {
        val m = GnssMonitor()
        m.onStatus(GnssStatusEvent(0.0, 10, 3, 22f)); m.onFix(fix(9_000.0), 1.0)
        assertEquals(GnssHealth.GOOD, m.assess(9_100.0).health) // статус старше 5 с
    }

    @Test fun uniformCn0IsSpoofingHallmark() {
        val m = GnssMonitor()
        m.onFix(fix(0.0), 1.0)
        m.onStatus(GnssStatusEvent(0.0, 12, 12, 40f, 0.8f, 41f))
        val a = m.assess(100.0)
        assertEquals(GnssHealth.UNTRUSTED, a.health); assertTrue(GnssReason.UNIFORM_CN0 in a.reasons)
    }

    @Test fun jumpIsFlaggedAndHeldForTenSeconds() {
        val m = GnssMonitor()
        m.onFix(fix(0.0), 1.0)
        m.onFix(fix(1_000.0, lat = 55.80), null) // 5.5 км за 1 с
        assertTrue(GnssReason.JUMP in m.assess(1_500.0).reasons)
        m.onFix(fix(2_000.0, lat = 55.80), null)
        assertTrue(GnssReason.JUMP in m.assess(10_900.0).reasons)
        m.onFix(fix(11_500.0, lat = 55.80), null)
        assertFalse(GnssReason.JUMP in m.assess(11_600.0).reasons)
    }

    @Test fun innovationLatchesAfterThreeBadFixesAndRelocks() {
        val m = GnssMonitor()
        m.onFix(fix(0.0), 50.0); m.onFix(fix(1_000.0), 50.0)
        assertFalse(GnssReason.INNOVATION in m.assess(1_100.0).reasons) // только две подряд
        m.onFix(fix(2_000.0), 50.0)
        assertEquals(GnssHealth.UNTRUSTED, m.assess(2_100.0).health)
        for (i in 3..13) m.onFix(fix(i * 1_000.0), 1.0) // согласуется 10 с: с 3-й по 13-ю секунду
        assertFalse(GnssReason.INNOVATION in m.assess(13_100.0).reasons)
    }

    @Test fun longLatchWithCleanIndependentSignsRequestsReinit() {
        val m = GnssMonitor()
        for (i in 0..2) { m.onStatus(goodStatus(i * 1_000.0)); m.onFix(fix(i * 1_000.0), 100.0) }
        assertTrue(GnssReason.INNOVATION in m.assess(2_100.0).reasons)
        for (i in 3..32) { m.onStatus(goodStatus(i * 1_000.0)); m.onFix(fix(i * 1_000.0), 100.0) }
        assertTrue(m.consumeReinit())
        assertFalse(m.consumeReinit()) // запрос одноразовый
        assertFalse(GnssReason.INNOVATION in m.assess(32_100.0).reasons)
    }

    @Test fun agcDropBelowLearnedBaselineDegrades() {
        val m = GnssMonitor()
        for (i in 0..20) {
            val t = i * 1_000.0
            m.onStatus(goodStatus(t)); m.onFix(fix(t), 1.0); m.onAgc(AgcEvent(t, 2f, 10))
        }
        m.onAgc(AgcEvent(21_000.0, -6f, 10)); m.onFix(fix(21_000.0), 1.0); m.onStatus(goodStatus(21_000.0))
        val a = m.assess(21_100.0)
        assertEquals(GnssHealth.DEGRADED, a.health); assertEquals(setOf(GnssReason.AGC_DROP), a.reasons)
    }

    @Test fun poorAccuracyDegrades() {
        val m = GnssMonitor()
        m.onFix(fix(0.0, acc = 35f), 1.0)
        assertEquals(Assessment(GnssHealth.DEGRADED, setOf(GnssReason.POOR_ACCURACY)), m.assess(100.0))
    }
}
