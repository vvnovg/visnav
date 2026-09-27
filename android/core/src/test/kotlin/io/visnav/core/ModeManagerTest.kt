package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ModeManagerTest {
    @Test fun degradesAfterTwoSecondsAndRecoversAfterTen() {
        val m = ModeManager()
        assertEquals(NavMode.GNSS, m.update(0.0, GnssHealth.GOOD, null))
        assertEquals(NavMode.GNSS, m.update(1_000.0, GnssHealth.DEGRADED, null))
        assertEquals(NavMode.GNSS, m.update(2_900.0, GnssHealth.DEGRADED, null))
        assertEquals(NavMode.FUSED, m.update(3_000.0, GnssHealth.DEGRADED, null))
        assertEquals(NavMode.FUSED, m.update(4_000.0, GnssHealth.GOOD, null))
        assertEquals(NavMode.FUSED, m.update(13_900.0, GnssHealth.GOOD, null))
        assertEquals(NavMode.GNSS, m.update(14_000.0, GnssHealth.GOOD, null))
    }

    @Test fun flickerDoesNotSwitch() {
        val m = ModeManager()
        m.update(0.0, GnssHealth.GOOD, null)
        m.update(1_000.0, GnssHealth.UNTRUSTED, null)
        m.update(2_500.0, GnssHealth.GOOD, null)   // цель сбросилась
        assertEquals(NavMode.GNSS, m.update(3_500.0, GnssHealth.UNTRUSTED, null))
    }

    @Test fun untrustedChoosesVisualOrDeadReckoningByVisualFreshness() {
        val m = ModeManager()
        m.update(0.0, GnssHealth.UNTRUSTED, lastVisualOkMs = 0.0)
        assertEquals(NavMode.VISUAL, m.update(2_000.0, GnssHealth.UNTRUSTED, lastVisualOkMs = 1_500.0))
        m.update(12_000.0, GnssHealth.UNTRUSTED, lastVisualOkMs = 1_500.0)  // фиксация устарела (>10 с)
        assertEquals(NavMode.DEAD_RECKONING, m.update(14_000.0, GnssHealth.UNTRUSTED, lastVisualOkMs = 1_500.0))
        assertEquals(NavMode.VISUAL, m.update(14_500.0, GnssHealth.UNTRUSTED, lastVisualOkMs = 14_400.0)) // сразу
    }

    @Test fun visualToGnssNeedsTenSecondsOfGoodSignal() {
        val m = ModeManager()
        m.update(0.0, GnssHealth.UNTRUSTED, 0.0); m.update(2_000.0, GnssHealth.UNTRUSTED, 1_900.0)
        assertEquals(NavMode.VISUAL, m.update(3_000.0, GnssHealth.GOOD, 2_900.0))
        assertEquals(NavMode.GNSS, m.update(13_000.0, GnssHealth.GOOD, 12_900.0))
    }
}
