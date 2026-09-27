package io.visnav.replay

import io.visnav.core.ClockEvent
import io.visnav.core.GyroEvent
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

    @Test fun clockDriftMaxMsIsNullWhenNoClockEvents() {
        assertNull(clockDriftMaxMs(emptyList()))
    }

    @Test fun clockDriftMaxMsIsZeroWhenWallAndElapsedStayInSync() {
        val clocks = listOf(
            ClockEvent(0.0, wallMs = 1_000L, elapsedNs = 500_000_000L),
            ClockEvent(1_000.0, wallMs = 2_000L, elapsedNs = 1_500_000_000L),
        )
        assertEquals(0L, clockDriftMaxMs(clocks))
    }

    @Test fun clockDriftMaxMsPicksTheLargestDeviationFromTheFirstOffset() {
        val clocks = listOf(
            ClockEvent(0.0, wallMs = 1_000L, elapsedNs = 0L), // offset0 = 1000
            ClockEvent(1_000.0, wallMs = 2_030L, elapsedNs = 1_000_000_000L), // expected 2000, drift 30
            ClockEvent(2_000.0, wallMs = 2_990L, elapsedNs = 2_000_000_000L), // expected 3000, drift 10
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
