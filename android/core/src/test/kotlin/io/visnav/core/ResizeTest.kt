package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResizeTest {
    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun blockAverage4x4to2x2GivesExactBlockColors() {
        val c00 = argb(10, 20, 30)
        val c01 = argb(40, 50, 60)
        val c10 = argb(70, 80, 90)
        val c11 = argb(100, 110, 120)
        // 4x4 image made of four 2x2 blocks: top-left=c00, top-right=c01, bottom-left=c10, bottom-right=c11.
        val src = IntArray(16)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                src[y * 4 + x] = when {
                    y < 2 && x < 2 -> c00
                    y < 2 && x >= 2 -> c01
                    y >= 2 && x < 2 -> c10
                    else -> c11
                }
            }
        }
        val dst = Resize.areaDownscale(src, 4, 4, 2, 2)
        assertContentEquals(intArrayOf(c00, c01, c10, c11), dst)
    }

    @Test fun fractional3x1to2x1() {
        val src = intArrayOf(argb(0, 0, 0), argb(90, 90, 90), argb(180, 180, 180))
        val dst = Resize.areaDownscale(src, 3, 1, 2, 1)
        assertEquals(argb(30, 30, 30), dst[0])
        assertEquals(argb(150, 150, 150), dst[1])
    }

    @Test fun sameSizeReturnsEqualContent() {
        val src = intArrayOf(argb(1, 2, 3), argb(4, 5, 6), argb(7, 8, 9), argb(10, 11, 12))
        val dst = Resize.areaDownscale(src, 2, 2, 2, 2)
        assertContentEquals(src, dst)
    }

    @Test fun upscaleThrows() {
        val src = intArrayOf(argb(1, 2, 3), argb(4, 5, 6))
        assertFailsWith<IllegalArgumentException> { Resize.areaDownscale(src, 2, 1, 4, 1) }
    }
}
