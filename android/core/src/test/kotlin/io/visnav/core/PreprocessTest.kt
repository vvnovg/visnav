package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PreprocessTest {
    @Test fun argbToRgbDropsAlphaKeepsOrder() {
        val px = intArrayOf(0xFF102030.toInt(), 0x80FF0001.toInt())
        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30, 0xFF.toByte(), 0x00, 0x01), Preprocess.argbToRgb(px))
    }

    @Test fun cosine() {
        assertEquals(1f, Vectors.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6f)
        assertEquals(0f, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 3f)), 1e-6f)
    }
}
