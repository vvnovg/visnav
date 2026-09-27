package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HalfTest {
    private fun h(bits: Int) = Half.toFloat(bits.toShort())

    @Test fun normals() {
        assertEquals(1.0f, h(0x3C00))
        assertEquals(-2.0f, h(0xC000))
        assertEquals(0.5f, h(0x3800))
        assertEquals(0.60009765625f, h(0x38CD)) // numpy float16(0.6)
        assertEquals(0.7998046875f, h(0x3A66))  // numpy float16(0.8)
    }

    @Test fun zerosSubnormalsAndSpecials() {
        assertEquals(0.0f, h(0x0000))
        assertEquals(-0.0f, h(0x8000))
        assertEquals(5.9604645e-8f, h(0x0001))
        assertEquals(Float.POSITIVE_INFINITY, h(0x7C00))
        assertEquals(Float.NEGATIVE_INFINITY, h(0xFC00))
        assertTrue(h(0x7E00).isNaN())
    }

    @Test fun lutMatchesFunction() {
        for (bits in listOf(0x0000, 0x0001, 0x3C00, 0x38CD, 0xC000, 0x7BFF)) {
            assertEquals(Half.toFloat(bits.toShort()), Half.LUT[bits])
        }
    }
}
