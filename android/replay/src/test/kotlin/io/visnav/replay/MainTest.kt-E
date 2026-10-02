package io.visnav.replay

import io.visnav.core.AccelEvent
import io.visnav.core.ClockEvent
import io.visnav.core.FrameCaptureEvent
import io.visnav.core.GyroEvent
import io.visnav.core.LocEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {
    @Test fun firstSensorTimeOkAcceptsWithinTolerance() {
        assertTrue(firstSensorTimeOk(listOf(GyroEvent(1_000.0, 0f, 0f, 0f)), startedMs = 4_000, toleranceMs = 5_000.0))
        assertTrue(firstSensorTimeOk(emptyList(), startedMs = 4_000)) // nothing to check
    }

    @Test fun firstSensorTimeOkRejectsBeyondTolerance() {
        assertFalse(firstSensorTimeOk(listOf(GyroEvent(20_000.0, 0f, 0f, 0f)), startedMs = 4_000, toleranceMs = 5_000.0))
    }

    @Test fun firstSensorTimeOkUsesTheEarliestEventNotListOrder() {
        val sensors = listOf(GyroEvent(20_000.0, 0f, 0f, 0f), GyroEvent(4_100.0, 0f, 0f, 0f))
        assertTrue(firstSensorTimeOk(sensors, startedMs = 4_000, toleranceMs = 5_000.0))
    }

    // Regression: FrameCaptureEvent.t is the frame's own wall time, NOT converted through the
    // elapsedRealtimeNanos-calibrated SensorRecorder offset (camera timestamps aren't reliably on
    // that clock on every device) — an early/bogus one must not fail the whole-session check.
    @Test fun firstSensorTimeOkIgnoresNonImuEvents() {
        val sensors = listOf(
            FrameCaptureEvent(-999_999.0, -999_999L, 123L), // camera clock could read hours off
            LocEvent(-999_999.0, 55.75, 37.6, 3f, null, null, null, null), // GPS fix time, also not IMU
            GyroEvent(4_100.0, 0f, 0f, 0f),
            AccelEvent(4_150.0, 0f, 0f, 9.8f),
        )
        assertTrue(firstSensorTimeOk(sensors, startedMs = 4_000, toleranceMs = 5_000.0))
    }

    @Test fun firstSensorTimeOkFailsWhenTheImuItselfIsShiftedLater() {
        val sensors = listOf(
            FrameCaptureEvent(4_050.0, 4_050L, 123L), // plausible on its own, but irrelevant to the check
            GyroEvent(20_000.0, 0f, 0f, 0f), // the actual IMU clock disagrees with started_ms
        )
        assertFalse(firstSensorTimeOk(sensors, startedMs = 4_000, toleranceMs = 5_000.0))
    }

    @Test fun firstImuEventReturnsNullWhenOnlyNonImuEventsArePresent() {
        assertNull(firstImuEvent(listOf(FrameCaptureEvent(1.0, 1L, 1L))))
    }

    @Test fun clockDriftMaxMsIsNullWhenNoClockEvents() {
        assertNull(clockDriftMaxMs(emptyList()))
    }

    @Test fun clockDriftMaxMsIsZeroWhenWallAndElapsedStayInSync() {
        val clocks = listOf(
            ClockEvent(0.0, wallMs = 1_000L, elapsedNs = 500_000_000L, monoNs = 0L),
            ClockEvent(1_000.0, wallMs = 2_000L, elapsedNs = 1_500_000_000L, monoNs = 0L),
        )
        assertEquals(0L, clockDriftMaxMs(clocks))
    }

    @Test fun clockDriftMaxMsPicksTheLargestDeviationFromTheFirstOffset() {
        val clocks = listOf(
            ClockEvent(0.0, wallMs = 1_000L, elapsedNs = 0L, monoNs = 0L), // offset0 = 1000
            ClockEvent(1_000.0, wallMs = 2_030L, elapsedNs = 1_000_000_000L, monoNs = 0L), // expected 2000, drift 30
            ClockEvent(2_000.0, wallMs = 2_990L, elapsedNs = 2_000_000_000L, monoNs = 0L), // expected 3000, drift 10
        )
        assertEquals(30L, clockDriftMaxMs(clocks))
    }

    @Test fun allFramesLackDescriptorTrueOnlyWhenEveryPointIsNoDesc() {
        val allNoDesc = listOf(
            TrajPoint(1, 0.0, 0.0, 1.0, false, null, null, "no_desc", false),
            TrajPoint(2, 0.0, 0.0, 1.0, false, null, null, "no_desc", false),
        )
        assertTrue(allFramesLackDescriptor(allNoDesc))

        val oneOk = allNoDesc + TrajPoint(3, 0.0, 0.0, 1.0, false, 0.9f, true, "ok", false)
        assertFalse(allFramesLackDescriptor(oneOk))

        assertFalse(allFramesLackDescriptor(emptyList())) // no points at all isn't the same failure
    }
}
