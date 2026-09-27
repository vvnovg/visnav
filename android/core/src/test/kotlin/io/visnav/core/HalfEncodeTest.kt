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
}
