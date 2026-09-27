package io.visnav.core

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionTest {
    @Test fun yawRateFlatPhone() {
        val y = YawRate()
        assertNull(y.headingRate(0f, 0f, 0.1f))
        y.onAccel(0f, 0f, 9.81f)
        // поворот против часовой вокруг «вверх» = курс уменьшается
        assertEquals(-0.1, y.headingRate(0f, 0f, 0.1f)!!, 1e-6)
    }

    @Test fun yawRatePhoneInLandscapeMount() {
        val y = YawRate()
        repeat(200) { y.onAccel(9.81f, 0f, 0f) } // вертикаль вдоль оси x телефона
        assertEquals(-0.2, y.headingRate(0.2f, 0f, 0f)!!, 1e-6)
        assertEquals(0.0, y.headingRate(0f, 0.3f, 0f)!!, 1e-6) // вращение вокруг горизонтальной оси — не поворот
    }

    @Test fun stationaryWhenQuiet() {
        val d = StationaryDetector()
        for (i in 0 until 100) {
            val t = i * 10.0
            d.onAccel(t, 0f, 0f, 9.81f + 0.01f * sin(i.toFloat()))
            d.onGyro(t, 0.001f, 0f, 0f)
        }
        assertTrue(d.isStationary(990.0))
    }

    @Test fun movingWhenVibratingOrTurning() {
        val vib = StationaryDetector()
        for (i in 0 until 100) { val t = i * 10.0; vib.onAccel(t, 0f, 0f, 9.81f + if (i % 2 == 0) 0.5f else -0.5f); vib.onGyro(t, 0f, 0f, 0f) }
        assertFalse(vib.isStationary(990.0))
        val turn = StationaryDetector()
        for (i in 0 until 100) { val t = i * 10.0; turn.onAccel(t, 0f, 0f, 9.81f); turn.onGyro(t, 0f, 0f, 0.1f) }
        assertFalse(turn.isStationary(990.0))
    }

    @Test fun notEnoughSamplesIsNotStationary() {
        val d = StationaryDetector()
        for (i in 0 until 5) { d.onAccel(i * 10.0, 0f, 0f, 9.81f); d.onGyro(i * 10.0, 0f, 0f, 0f) }
        assertFalse(d.isStationary(50.0))
    }

    @Test fun oldSamplesLeaveTheWindow() {
        val d = StationaryDetector()
        for (i in 0 until 100) { d.onAccel(i * 10.0, 0f, 0f, 9.81f); d.onGyro(i * 10.0, 0f, 0f, 0f) }
        assertFalse(d.isStationary(5_000.0)) // в окне [4000, 5000] ничего нет
    }
}
