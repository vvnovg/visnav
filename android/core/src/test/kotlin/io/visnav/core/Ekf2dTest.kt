package io.visnav.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ekf2dTest {
    private fun filter(psi: Double = PI / 2, v: Double = 10.0) =
        Ekf2d().also { it.init(0.0, 0.0, psi, v, posSigma = 3.0, psiSigma = 0.05, vSigma = 0.5) }

    @Test fun straightEastDrive() {
        val f = filter()
        repeat(100) { f.predict(0.1, 0.0) }
        assertEquals(100.0, f.x[0], 1e-6); assertEquals(0.0, f.x[1], 1e-6)
        assertTrue(f.posSigma() > 3.0) // неопределённость растёт без измерений
    }

    @Test fun gyroTurnChangesHeadingClockwise() {
        val f = filter()
        repeat(1000) { f.predict(0.01, 0.1) } // 10 с по 0.1 рад/с по часовой
        assertEquals(wrapAngle(PI / 2 + 1.0), f.x[2], 1e-9)
    }

    @Test fun positionUpdatePullsStateAndShrinksCovariance() {
        val f = filter()
        repeat(50) { f.predict(0.1, 0.0) }
        val before = f.posSigma()
        assertTrue(f.updatePosition(60.0, 5.0, 3.0))
        assertTrue(f.x[0] > 50.0 && f.x[0] < 60.0)
        assertTrue(f.x[1] > 0.0 && f.x[1] < 5.0)
        assertTrue(f.posSigma() < before)
    }

    @Test fun estimatesGyroBiasFromGnss() {
        val f = filter()
        var truthE = 0.0
        for (s in 1..120) {
            repeat(100) { f.predict(0.01, 0.02) } // истинный поворот 0, гироскоп врёт на +0.02 рад/с
            truthE += 10.0
            f.updatePosition(truthE, 0.0, 3.0)
            f.updateHeading(PI / 2, 0.03)
            f.updateSpeed(10.0, 0.2)
        }
        assertEquals(0.02, f.x[4], 0.005)
    }

    @Test fun gateRejectsFarOutlierAndKeepsState() {
        val f = filter()
        repeat(10) { f.predict(0.1, 0.0); f.updatePosition(f.x[0], 0.0, 3.0) }
        val e = f.x[0]; val n = f.x[1]
        assertFalse(f.updatePosition(e + 500.0, n, 5.0))
        assertEquals(e, f.x[0]); assertEquals(n, f.x[1])
    }

    @Test fun headingInnovationWrapsAround() {
        val f = filter(psi = Math.toRadians(1.0), v = 0.0)
        assertTrue(f.updateHeading(Math.toRadians(359.0), 0.05))
        assertTrue(f.x[2] < Math.toRadians(1.0) && f.x[2] > Math.toRadians(-1.0)) // сдвинулось через 0, а не на 358°
    }

    @Test fun wrapAngleRange() {
        assertEquals(-PI / 2, wrapAngle(3 * PI / 2), 1e-12)
        assertEquals(PI, wrapAngle(-PI), 1e-12)
    }
}
