package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameRateGovernorTest {
    private fun FrameRateGovernor.calm(nowMs: Long, mode: NavMode? = NavMode.VISUAL, health: GnssHealth? = GnssHealth.UNTRUSTED) =
        update(nowMs, thermal = 0, headroom = 0.3, mode = mode, health = health)

    @Test fun startsAtFastestStep() {
        val g = FrameRateGovernor()
        assertEquals(500L, g.intervalMs)
        assertEquals(500L, g.calm(0))
    }

    @Test fun moderateThermalSlowsWithCooldown() {
        val g = FrameRateGovernor()
        assertEquals(750L, g.update(0, 2, null, NavMode.VISUAL, GnssHealth.UNTRUSTED))
        assertEquals(750L, g.update(5_000, 2, null, NavMode.VISUAL, GnssHealth.UNTRUSTED))   // пауза 10 с
        assertEquals(1000L, g.update(10_000, 2, null, NavMode.VISUAL, GnssHealth.UNTRUSTED))
        assertEquals(1000L, g.intervalMs)
    }

    @Test fun severeThermalJumpsToSlowestAndRecoversStepwise() {
        val g = FrameRateGovernor()
        assertEquals(2000L, g.update(0, 3, null, NavMode.VISUAL, GnssHealth.UNTRUSTED))
        assertEquals(2000L, g.calm(30_000))
        assertEquals(1500L, g.calm(60_000))
        assertEquals(1500L, g.calm(90_000))
        assertEquals(1000L, g.calm(120_000))
    }

    @Test fun headroomForecastSlows() {
        val g = FrameRateGovernor()
        assertEquals(750L, g.update(0, 0, 0.9, NavMode.VISUAL, GnssHealth.UNTRUSTED))
    }

    @Test fun slowFramesSlowDownFastFramesDoNot() {
        val slow = FrameRateGovernor()
        repeat(20) { slow.onFrameCost(300.0) }
        assertEquals(750L, slow.calm(0))

        val fast = FrameRateGovernor()
        repeat(20) { fast.onFrameCost(100.0) }
        assertEquals(500L, fast.calm(0))
    }

    @Test fun p90UsesOnlyLastWindow() {
        val g = FrameRateGovernor()
        repeat(20) { g.onFrameCost(300.0) }
        repeat(20) { g.onFrameCost(100.0) }   // старые долгие кадры вытеснены
        assertEquals(500L, g.calm(0))
    }

    @Test fun recoversOneStepPerMinuteDownToFastest() {
        val g = FrameRateGovernor()
        g.update(0, 2, null, NavMode.VISUAL, GnssHealth.UNTRUSTED)
        g.update(10_000, 2, null, NavMode.VISUAL, GnssHealth.UNTRUSTED)
        assertEquals(1000L, g.intervalMs)
        assertEquals(1000L, g.calm(69_999))
        assertEquals(750L, g.calm(70_000))
        assertEquals(750L, g.calm(100_000))
        assertEquals(500L, g.calm(130_000))
        assertEquals(500L, g.calm(190_000))
        assertEquals(500L, g.calm(250_000))
    }

    @Test fun badConditionResetsRecoveryClock() {
        val g = FrameRateGovernor()
        g.update(0, 2, null, NavMode.VISUAL, GnssHealth.UNTRUSTED)                // 750
        assertEquals(1000L, g.update(50_000, 2, null, NavMode.VISUAL, GnssHealth.UNTRUSTED))
        assertEquals(1000L, g.calm(100_000))                                       // 50 с без условий — мало
        assertEquals(750L, g.calm(110_000))
    }

    @Test fun gnssGoodFloorAndNoJumpOnLeaving() {
        val g = FrameRateGovernor()
        assertEquals(1000L, g.calm(0, NavMode.GNSS, GnssHealth.GOOD))
        assertEquals(1000L, g.calm(120_000, NavMode.GNSS, GnssHealth.GOOD))
        assertEquals(1000L, g.calm(121_000, NavMode.VISUAL, GnssHealth.UNTRUSTED)) // без скачка
        assertEquals(1000L, g.calm(179_999, NavMode.VISUAL, GnssHealth.UNTRUSTED))
        assertEquals(750L, g.calm(180_000, NavMode.VISUAL, GnssHealth.UNTRUSTED))
        assertEquals(500L, g.calm(240_000, NavMode.VISUAL, GnssHealth.UNTRUSTED))
    }

    @Test fun gnssFloorOnlyWhenHealthGood() {
        val g = FrameRateGovernor()
        assertEquals(500L, g.calm(0, NavMode.GNSS, GnssHealth.DEGRADED))
        assertEquals(500L, g.calm(1_000, NavMode.FUSED, GnssHealth.GOOD))
    }

    @Test fun gnssFloorDoesNotSpeedUpSlowerSteps() {
        val g = FrameRateGovernor()
        assertEquals(2000L, g.update(0, 3, null, NavMode.GNSS, GnssHealth.GOOD))
        assertEquals(1500L, g.calm(60_000, NavMode.GNSS, GnssHealth.GOOD))
        assertEquals(1000L, g.calm(120_000, NavMode.GNSS, GnssHealth.GOOD))
        assertEquals(1000L, g.calm(180_000, NavMode.GNSS, GnssHealth.GOOD))
    }

    @Test fun worksWithoutThermalData() {
        val g = FrameRateGovernor()
        assertEquals(500L, g.update(0, null, null, null, null))
        repeat(20) { g.onFrameCost(400.0) }
        assertEquals(750L, g.update(1_000, null, null, null, null))
        assertEquals(1000L, g.update(2_000, null, null, NavMode.GNSS, GnssHealth.GOOD))
        val h = FrameRateGovernor()
        assertEquals(1000L, h.update(0, null, null, NavMode.GNSS, GnssHealth.GOOD))
    }
}
