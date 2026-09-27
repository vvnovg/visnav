package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HalfEncodeTest {
    private fun bits(f: Float) = Half.fromFloat(f).toInt() and 0xFFFF

    @Test fun knownValues() {
        assertEquals(0x3C00, bits(1.0f))
        assertEquals(0xC000, bits(-2.0f))
        assertEquals(0x38CD, bits(0.6f))   // как numpy.float16(0.6)
        assertEquals(0x3A66, bits(0.8f))
        assertEquals(0x0001, bits(5.9604645e-8f)) // наименьшее субнормальное
        assertEquals(0x0000, bits(0.0f))
        assertEquals(0x8000, bits(-0.0f))
        assertEquals(0x7BFF, bits(65504f))
    }

    @Test fun overflowUnderflowAndSpecials() {
        assertEquals(0x7C00, bits(65520f))            // ровно посередине → к чётному → inf
        assertEquals(0x7C00, bits(1e10f))
        assertEquals(0xFC00, bits(Float.NEGATIVE_INFINITY))
        assertEquals(0x0000, bits(1e-10f))
        assertTrue(Half.toFloat(Half.fromFloat(Float.NaN)).isNaN())
    }

    @Test fun roundTripsEveryNonNanHalf() {
        for (h in 0 until 65536) {
            val f = Half.toFloat(h.toShort())
            if (f.isNaN()) continue
            assertEquals(h, bits(f), "half 0x${h.toString(16)}")
        }
    }

    @Test fun tieBreakerAndCarryCases() {
        // Halfway between 1.0 and next half (1.0 + 2^-10 in half terms):
        // Exactly 1.0 + 2^-11 rounds to 0x3C00 by round-to-even (1.0 is even in half)
        val halfway1 = Math.scalb(1f, -11).toFloat()  // 2^-11, adds to 1.0
        val tied = 1.0f + halfway1
        assertEquals(0x3C00, bits(tied), "1.0 + 2^-11 (tie to even) → 0x3C00")

        // Value above halfway rounds to next representable: 1.0 + 3·2^-11 → 0x3C02
        val above = 1.0f + 3 * halfway1
        assertEquals(0x3C02, bits(above), "1.0 + 3·2^-11 → 0x3C02")

        // Subnormal that rounds up to smallest normal (0x0400 = 2^-14):
        // Value slightly above 2^-14 - 2^-24 should round to 0x0400
        val nearBoundary = Math.scalb(1f, -14).toFloat() + Math.scalb(1f, -25).toFloat()
        assertEquals(0x0400, bits(nearBoundary), "near 2^-14 rounds to normal 0x0400")

        // Normal exponent boundary: value between 2^1 and 2^1 + 2^-10 in half precision
        // 2.0 - 2^-12 should encode as 0x4000
        val exp2 = 2.0f - Math.scalb(1f, -12).toFloat()
        assertEquals(0x4000, bits(exp2), "2.0 - 2^-12 → 0x4000 (normal exponent carry)")
    }
}
