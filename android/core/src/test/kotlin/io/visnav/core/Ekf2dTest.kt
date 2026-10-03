package io.visnav.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            assertTrue(f.updatePosition(truthE, 0.0, 3.0))
            assertTrue(f.updateHeading(PI / 2, 0.03))
            assertTrue(f.updateSpeed(10.0, 0.2))
        }
        assertEquals(0.02, f.x[4], 0.005)
    }

    @Test fun estimatesGyroBiasWithoutHeadingUpdates() {
        val psi0 = 0.7
        val f = filter(psi = psi0, v = 10.0)
        var truthE = 0.0
        var truthN = 0.0
        for (s in 1..120) {
            repeat(100) { f.predict(0.01, 0.02) } // истинный поворот 0, гироскоп врёт на +0.02 рад/с
            truthE += 10.0 * sin(psi0)
            truthN += 10.0 * cos(psi0)
            assertTrue(f.updatePosition(truthE, truthN, 3.0))
            assertTrue(f.updateSpeed(10.0, 0.2))
            // без updateHeading: смещение гироскопа должно всё равно оцениваться по позиции
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

    @Test fun updatePositionRejectsNaNAndKeepsState() {
        val f = filter()
        repeat(10) { f.predict(0.1, 0.0) }
        val xBefore = f.x.copyOf()
        val pBefore = f.p.copyOf()
        assertFalse(f.updatePosition(Double.NaN, 0.0, 3.0))
        assertEquals(xBefore.toList(), f.x.toList())
        assertEquals(pBefore.toList(), f.p.toList())
    }

    @Test fun zeroSigmaThrows() {
        val f = filter()
        assertFailsWith<IllegalArgumentException> { f.updatePosition(1.0, 1.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { f.updateSpeed(1.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { f.updateHeading(0.0, 0.0) }
    }

    @Test fun gateBoundaryOnPositionUpdate() {
        // Свежая инициализация: P[e,e]=P[n,n]=9, без предсказаний P вне диагонали равна 0.
        // При sigma=4 (R=16) S = 25·I, значит d² = смещение²/25 вдоль одной оси.
        val accept = filter()
        val rAccept = sqrt(13.7 * 25.0)
        assertTrue(accept.updatePosition(rAccept, 0.0, 4.0))

        val reject = filter()
        val rReject = sqrt(13.9 * 25.0)
        assertFalse(reject.updatePosition(rReject, 0.0, 4.0))
    }

    @Test fun positionD2MatchesUpdateGateAndDoesNotChangeState() {
        val f = Ekf2d().also { it.init(0.0, 0.0, 0.0, 0.0, posSigma = 3.0, psiSigma = 0.1, vSigma = 1.0) }
        val before = f.x.copyOf() to f.p.copyOf()
        // S = (9 + 16)·I = 25·I, смещение 10 м по востоку → d² = 100/25 = 4
        assertEquals(4.0, f.positionD2(10.0, 0.0, 4.0), 1e-9)
        assertContentEquals(before.first, f.x); assertContentEquals(before.second, f.p)
        assertFailsWith<IllegalArgumentException> { f.positionD2(0.0, 0.0, 0.0) }
    }

    @Test fun updateLateralPullsOnlyAcrossTheRoad() {
        val ekf = Ekf2d()
        ekf.init(0.0, 10.0, Math.PI / 2, 10.0, 10.0, 0.1, 1.0)
        // Дорога на восток через (0, 0): поперечное смещение = 10 м к северу.
        assertTrue(ekf.updateLateral(0.0, 0.0, Math.PI / 2, 4.0))
        assertTrue(ekf.x[1] < 3.0, "n=${ekf.x[1]}")
        assertEquals(0.0, ekf.x[0], 1e-9)
    }

    @Test fun updateLateralIsGated() {
        val ekf = Ekf2d()
        ekf.init(0.0, 1000.0, Math.PI / 2, 10.0, 3.0, 0.1, 1.0)
        assertFalse(ekf.updateLateral(0.0, 0.0, Math.PI / 2, 4.0))
        assertEquals(1000.0, ekf.x[1], 1e-9)
    }

    @Test fun updateLateralMovesOnlyPosition() {
        val f = filter(psi = PI / 4)
        repeat(50) { f.predict(0.1, 0.02) }
        assertTrue(f.p[2] != 0.0 && f.p[3] != 0.0 && f.p[4] != 0.0, "P must carry e-psi/e-v/e-bg cross terms")
        val before = f.x.copyOf()
        assertTrue(f.updateLateral(f.x[0] + 5.0, f.x[1] - 5.0, PI / 2, 4.0))
        assertEquals(before[2], f.x[2], 0.0); assertEquals(before[3], f.x[3], 0.0); assertEquals(before[4], f.x[4], 0.0)
        assertTrue(f.x[1] != before[1])
    }

    @Test fun lateralVarianceProjectsCovariance() {
        val f = filter()
        f.p.fill(0.0)
        f.p[0] = 4.0; f.p[1] = 1.5; f.p[5] = 1.5; f.p[6] = 9.0 // Pee=4, Pen=1.5, Pnn=9
        assertEquals(9.0, f.lateralVariance(PI / 2), 1e-12)
        assertEquals(4.0, f.lateralVariance(0.0), 1e-12)
        val th = PI / 4 // n = (cos, -sin) = (s, -s), s = sqrt(1/2): 0.5·(Pee + Pnn) − Pen
        assertEquals(0.5 * (4.0 + 9.0) - 1.5, f.lateralVariance(th), 1e-12)
    }

    @Test fun updateLateralDisplacesOnlyAlongTheNormalWithAnisotropicP() {
        val f = filter(psi = PI / 4)
        repeat(50) { f.predict(0.1, 0.02) }
        val e0 = f.x[0]; val n0 = f.x[1]
        assertTrue(f.updateLateral(e0 + 4.0, n0 - 4.0, PI / 2, 4.0)) // дорога на восток: смещение только по n
        assertEquals(e0, f.x[0], 1e-9)
        assertTrue(f.x[1] != n0)
    }
}
