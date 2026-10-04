package io.visnav.core

import kotlin.test.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test fun headingSouth() {
        val e = en(Nowcast.at(out(10.0, 180.0), 2000L))
        assertEquals(0.0, e[0], 0.05)
        assertEquals(-10.0, e[1], 0.05)
    }

    @Test fun headingSouthWest() {
        val e = en(Nowcast.at(out(10.0, 225.0), 2000L))
        assertEquals(-10.0 / sqrt(2.0), e[0], 0.05)
        assertEquals(-10.0 / sqrt(2.0), e[1], 0.05)
    }

    @Test fun infiniteSpeedNoShift() {
        val p = Nowcast.at(out(Double.POSITIVE_INFINITY, 90.0), 2000L)
        assertEquals(lat0, p[0]); assertEquals(lon0, p[1])
    }

    @Test fun nearPoleOrNonFiniteLatNoShift() {
        for (lat in listOf(89.5, -89.5, Double.NaN)) {
            val p = Nowcast.at(out(10.0, 90.0).copy(lat = lat), 2000L)
            assertEquals(lat, p[0]); assertEquals(lon0, p[1])
        }
    }

    @Test fun lonWrapsAcrossAntimeridian() {
        val o = out(10.0, 90.0).copy(lat = 0.0, lon = 179.99995)
        val p = Nowcast.at(o, 4000L)
        val expected = 179.99995 + 30.0 / (Math.PI / 180.0 * Geo.EARTH_RADIUS_M) - 360.0
        assertEquals(0.0, p[0], 1e-9)
        assertEquals(expected, p[1], 1e-9)
        assertTrue(p[1] >= -180.0 && p[1] < 180.0)
    }

    @Test fun negativeMaxAheadRejected() {
        assertFailsWith<IllegalArgumentException> { Nowcast.at(out(10.0, 90.0), 2000L, maxAheadMs = -1) }
    }
}
