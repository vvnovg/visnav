package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class Rotate90Test {
    // 3×2 (w=3, h=2):
    //   1 2 3
    //   4 5 6
    private val src = intArrayOf(1, 2, 3, 4, 5, 6)

    @Test fun rotate0IsCopy() {
        val out = Rotate90.rotate(src, 3, 2, 0)
        assertContentEquals(src, out)
    }

    @Test fun rotate90Clockwise() {
        // 2×3: (x, y) → (h−1−y, x)
        //   4 1
        //   5 2
        //   6 3
        assertContentEquals(intArrayOf(4, 1, 5, 2, 6, 3), Rotate90.rotate(src, 3, 2, 90))
    }

    @Test fun rotate180() {
        //   6 5 4
        //   3 2 1
        assertContentEquals(intArrayOf(6, 5, 4, 3, 2, 1), Rotate90.rotate(src, 3, 2, 180))
    }

    @Test fun rotate270() {
        // 2×3:
        //   3 6
        //   2 5
        //   1 4
        assertContentEquals(intArrayOf(3, 6, 2, 5, 1, 4), Rotate90.rotate(src, 3, 2, 270))
    }

    @Test fun otherAnglesThrow() {
        assertFailsWith<IllegalArgumentException> { Rotate90.rotate(src, 3, 2, 45) }
    }
}
