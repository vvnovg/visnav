package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CameraPolicyTest {
    @Test fun zoomBySpeed() {
        val c = CameraPolicy()
        assertEquals(17.0, c.zoomFor(5.0)); assertEquals(16.0, c.zoomFor(10.0))
        assertEquals(15.5, c.zoomFor(20.0)); assertEquals(15.0, c.zoomFor(30.0))
    }

    @Test fun headingUpOnlyWhenMoving() {
        val c = CameraPolicy()
        assertEquals(90.0, c.update(0, 55.75, 37.6, 10.0, Math.PI / 2)!!.bearingDeg, 1e-9)
        assertEquals(90.0, c.update(500, 55.75, 37.6, 1.0, Math.PI)!!.bearingDeg, 1e-9)   // стоим — держим курс
        assertEquals(270.0, c.update(1000, 55.75, 37.6, 10.0, -Math.PI / 2)!!.bearingDeg, 1e-9)
    }

    @Test fun gestureFreesCameraUntilRecenterOrTimeout() {
        val c = CameraPolicy(freeHoldMs = 10_000)
        c.onUserGesture(1_000)
        assertNull(c.update(2_000, 55.75, 37.6, 10.0, 0.0))
        assertNotNull(c.update(11_001, 55.75, 37.6, 10.0, 0.0))
        c.onUserGesture(12_000)
        c.recenter()
        assertNotNull(c.update(12_500, 55.75, 37.6, 10.0, 0.0))
    }

    @Test fun freeTimeoutBoundaryAndClockReset() {
        val c = CameraPolicy(freeHoldMs = 10_000)
        c.onUserGesture(1_000)
        assertNull(c.update(11_000, 55.75, 37.6, 10.0, 0.0))
        assertNotNull(c.update(11_001, 55.75, 37.6, 10.0, 0.0))
        c.onUserGesture(50_000)
        assertNotNull(c.update(100, 55.75, 37.6, 10.0, 0.0))   // часы сброшены — свободный режим истёк
    }

    @Test fun speedThresholdAndNaNSpeed() {
        val c = CameraPolicy()
        assertEquals(90.0, c.update(0, 55.75, 37.6, 3.0, Math.PI / 2)!!.bearingDeg, 1e-9)
        val t = c.update(100, 55.75, 37.6, Double.NaN, Math.PI)!!
        assertEquals(15.0, t.zoom); assertEquals(90.0, t.bearingDeg, 1e-9)
    }
}
