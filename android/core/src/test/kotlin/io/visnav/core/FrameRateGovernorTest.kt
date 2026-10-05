package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameRateGovernorTest {
    private fun FrameRateGovernor.calm(nowMs: Long, mode: NavMode? = NavMode.VISUAL, health: GnssHealth? = GnssHealth.UNTRUSTED) =
        update(nowMs, thermal = 0, headroom = 0.3, mode = mode, health = health)

    private fun FrameRateGovernor.moderate(nowMs: Long) = update(nowMs, 2, null, NavMode.VISUAL, GnssHealth.UNTRUSTED)

    /** Первые кадры (прогрев ORT) не учитываются. */
    private fun FrameRateGovernor.warmUp() = repeat(5) { onFrameCost(5_000.0) }

    private fun FrameRateGovernor.frames(n: Int, ms: Double) = repeat(n) { onFrameCost(ms) }

    @Test fun startsAtFastestStep() {
        val g = FrameRateGovernor()
        assertEquals(500L, g.intervalMs)
        assertEquals(500L, g.calm(0))
    }

    @Test fun moderateThermalSlowsWithCooldown() {
        val g = FrameRateGovernor()
        assertEquals(750L, g.moderate(0))
        assertEquals(750L, g.moderate(5_000))   // пауза 10 с
        assertEquals(1000L, g.moderate(10_000))
        assertEquals(1000L, g.intervalMs)
    }

    @Test fun cooldownBoundary() {
        val g = FrameRateGovernor()
        assertEquals(750L, g.moderate(0))
        assertEquals(750L, g.moderate(9_999))
        assertEquals(1000L, g.moderate(10_000))
    }

    @Test fun softRulesStopAt1500() {
        val g = FrameRateGovernor()
        assertEquals(750L, g.moderate(0))
        assertEquals(1000L, g.moderate(10_000))
        assertEquals(1500L, g.moderate(20_000))
        for (t in 30_000L..60_000L step 10_000) assertEquals(1500L, g.moderate(t))
    }

    @Test fun severeThermalJumpsToSlowestAndRecoversStepwise() {
        val g = FrameRateGovernor()
        assertEquals(2000L, g.update(0, 3, null, NavMode.VISUAL, GnssHealth.UNTRUSTED))
        assertEquals(2000L, g.calm(30_000))
        assertEquals(1500L, g.calm(60_000))
        assertEquals(1500L, g.calm(90_000))
        assertEquals(1000L, g.calm(120_000))
    }

    @Test fun moderateAfterSevereKeepsSlowest() {
        val g = FrameRateGovernor()
        assertEquals(2000L, g.update(0, 3, null, NavMode.VISUAL, GnssHealth.UNTRUSTED))
        assertEquals(2000L, g.moderate(20_000))
    }

    @Test fun headroomForecastSlows() {
        val g = FrameRateGovernor()
        assertEquals(750L, g.update(0, 0, 0.9, NavMode.VISUAL, GnssHealth.UNTRUSTED))
    }

    @Test fun headroomBoundarySlows() {
        val g = FrameRateGovernor()
        assertEquals(750L, g.update(0, 0, 0.85, NavMode.VISUAL, GnssHealth.UNTRUSTED))
    }

    @Test fun slowFramesSlowDownFastFramesDoNot() {
        val slow = FrameRateGovernor()
        slow.warmUp(); slow.frames(20, 300.0)
        assertEquals(750L, slow.calm(0))

        val fast = FrameRateGovernor()
        fast.warmUp(); fast.frames(20, 100.0)
        assertEquals(500L, fast.calm(0))
    }

    @Test fun warmUpFramesIgnored() {
        val g = FrameRateGovernor()
        g.warmUp(); g.frames(15, 100.0)   // с прогревом в окне было бы 20 кадров и p90 = 5 000
        assertEquals(500L, g.calm(0))
    }

    @Test fun warmUpDoesNotRestartAfterWindowClear() {
        val g = FrameRateGovernor()
        g.warmUp(); g.frames(20, 400.0)
        assertEquals(750L, g.calm(0))      // окно очищено
        g.frames(20, 400.0)                // все 20 идут в окно, прогрев не повторяется
        assertEquals(1000L, g.calm(10_000))
    }

    @Test fun p90BoundaryDoesNotSlow() {
        val g = FrameRateGovernor()
        g.warmUp(); g.frames(20, 250.0)
        assertEquals(500L, g.calm(0))
    }

    @Test fun twoSlowFramesOfTwentyDoNotSlow() {
        val g = FrameRateGovernor()
        g.warmUp(); g.frames(18, 100.0); g.frames(2, 400.0)
        assertEquals(500L, g.calm(0))
    }

    @Test fun partialWindowDoesNotSlow() {
        val g = FrameRateGovernor()
        g.warmUp(); g.frames(19, 400.0)
        assertEquals(500L, g.calm(0))
    }

    @Test fun windowClearedAfterP90Slowdown() {
        val g = FrameRateGovernor()
        g.warmUp(); g.frames(20, 100.0); g.frames(3, 400.0)
        assertEquals(750L, g.calm(0))
        g.frames(13, 100.0)
        assertEquals(750L, g.calm(10_000))   // старые долгие кадры не тянут дальше
    }

    @Test fun p90UsesOnlyLastWindow() {
        val g = FrameRateGovernor()
        g.warmUp(); g.frames(20, 300.0); g.frames(20, 100.0)   // старые долгие кадры вытеснены
        assertEquals(500L, g.calm(0))
    }

    @Test fun recoversOneStepPerMinuteDownToFastest() {
        val g = FrameRateGovernor()
        g.moderate(0)
        g.moderate(10_000)
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
        g.moderate(0)                                  // 750
        assertEquals(1000L, g.moderate(50_000))
        assertEquals(1000L, g.calm(100_000))           // 50 с без условий — мало
        assertEquals(750L, g.calm(110_000))
    }

    @Test fun timeGoingBackwardsResetsClocks() {
        val g = FrameRateGovernor()
        assertEquals(750L, g.moderate(10_000))
        assertEquals(750L, g.moderate(5_000))          // часы назад: отсчёт паузы с 5 000
        assertEquals(1000L, g.moderate(15_000))
        assertEquals(1000L, g.calm(0))                 // часы восстановления тоже с 0
        assertEquals(750L, g.calm(60_000))
    }

    @Test fun gnssGoodFloorAndImmediateReturnOnLeaving() {
        val g = FrameRateGovernor()
        assertEquals(1000L, g.calm(0, NavMode.GNSS, GnssHealth.GOOD))
        assertEquals(1000L, g.intervalMs)
        assertEquals(500L, g.calm(1_000, NavMode.VISUAL, GnssHealth.UNTRUSTED))
    }

    @Test fun heatUnderFloorCountsFromUnderlyingStep() {
        val g = FrameRateGovernor()
        val gnss = { t: Long -> g.update(t, 2, null, NavMode.GNSS, GnssHealth.GOOD) }
        assertEquals(1000L, gnss(0))         // под полом 750
        assertEquals(1000L, gnss(10_000))    // под полом 1000
        assertEquals(1500L, gnss(20_000))
        assertEquals(1500L, g.calm(21_000, NavMode.VISUAL, GnssHealth.UNTRUSTED))
    }

    @Test fun leavingFloorWhileHotDoesNotSpeedUp() {
        val g = FrameRateGovernor()
        assertEquals(1000L, g.calm(0, NavMode.GNSS, GnssHealth.GOOD))
        assertEquals(1000L, g.moderate(1_000))   // пол снят, но нагрев — не быстрее прежних 1000
        assertEquals(1000L, g.moderate(5_000))
    }

    @Test fun gnssFloorOnlyWhenHealthGood() {
        val g = FrameRateGovernor()
        assertEquals(500L, g.calm(0, NavMode.GNSS, GnssHealth.DEGRADED))
        assertEquals(500L, g.calm(1_000, NavMode.FUSED, GnssHealth.GOOD))
    }

    @Test fun underlyingStepFollowsRulesUnderFloor() {
        val g = FrameRateGovernor()
        assertEquals(2000L, g.update(0, 3, null, NavMode.GNSS, GnssHealth.GOOD))
        assertEquals(1500L, g.calm(60_000, NavMode.GNSS, GnssHealth.GOOD))
        assertEquals(1000L, g.calm(120_000, NavMode.GNSS, GnssHealth.GOOD))
        assertEquals(1000L, g.calm(180_000, NavMode.GNSS, GnssHealth.GOOD))   // под полом уже 750
        assertEquals(750L, g.calm(181_000, NavMode.VISUAL, GnssHealth.UNTRUSTED))
        assertEquals(500L, g.calm(240_000, NavMode.VISUAL, GnssHealth.UNTRUSTED))
    }

    @Test fun worksWithoutThermalData() {
        val g = FrameRateGovernor()
        assertEquals(500L, g.update(0, null, null, null, null))
        g.warmUp(); g.frames(20, 400.0)
        assertEquals(750L, g.update(1_000, null, null, null, null))
        assertEquals(1000L, g.update(2_000, null, null, NavMode.GNSS, GnssHealth.GOOD))
        val h = FrameRateGovernor()
        assertEquals(1000L, h.update(0, null, null, NavMode.GNSS, GnssHealth.GOOD))
    }
}
