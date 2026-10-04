package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NowcastTest {
    private val lat0 = 55.75
    private val lon0 = 37.62
    private fun out(speed: Double, psiDeg: Double) = LocalizerOutput(
        1000L, lat0, lon0, 3.0, null, null, "off", false, NavMode.FUSED, GnssHealth.GOOD, emptySet(),
        psiRad = Math.toRadians(psiDeg), speedMps = speed,
    )
    private fun en(p: DoubleArray) = Enu(lat0, lon0).toEn(p[0], p[1])

    @Test fun shiftsAlongHeading() {
        val e = en(Nowcast.at(out(10.0, 90.0), 2000L))
        assertEquals(10.0, e[0], 0.05)
        assertEquals(0.0, e[1], 0.05)
    }

    @Test fun slowSpeedNoShift() {
        val p = Nowcast.at(out(0.5, 90.0), 2000L)
        assertEquals(lat0, p[0]); assertEquals(lon0, p[1])
    }

    @Test fun nanHeadingNoShift() {
        val p = Nowcast.at(out(10.0, Double.NaN), 2000L)
        assertEquals(lat0, p[0]); assertEquals(lon0, p[1])
    }

    @Test fun aheadIsClamped() {
        val e = en(Nowcast.at(out(10.0, 0.0), 11_000L))
        assertEquals(0.0, e[0], 0.05)
        assertEquals(30.0, e[1], 0.05)
    }

    @Test fun negativeDtNoShift() {
        val p = Nowcast.at(out(10.0, 90.0), 500L)
        assertEquals(lat0, p[0]); assertEquals(lon0, p[1])
    }
}
