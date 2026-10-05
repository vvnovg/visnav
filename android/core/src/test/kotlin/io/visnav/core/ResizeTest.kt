package io.visnav.core

import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    /** Случайный ARGB-кадр (альфа 0xFF) и тот же кадр как RGBA_8888 с rowStride > w·4 (хвост строки — мусор). */
    private fun randomFrame(w: Int, h: Int, seed: Int, rowPad: Int): Pair<IntArray, ByteBuffer> {
        val rnd = Random(seed)
        val argbPx = IntArray(w * h) { argb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        val rowStride = w * 4 + rowPad
        val bytes = ByteArray(rowStride * h) { rnd.nextInt(256).toByte() }
        for (y in 0 until h) for (x in 0 until w) {
            val p = argbPx[y * w + x]
            val o = y * rowStride + x * 4
            bytes[o] = ((p shr 16) and 0xFF).toByte()
            bytes[o + 1] = ((p shr 8) and 0xFF).toByte()
            bytes[o + 2] = (p and 0xFF).toByte()
            bytes[o + 3] = 0xFF.toByte()
        }
        return argbPx to ByteBuffer.wrap(bytes)
    }

    private fun assertRgbaMatchesArgb(w: Int, h: Int, outW: Int, outH: Int, seed: Int, rowPad: Int) {
        val (argbPx, buf) = randomFrame(w, h, seed, rowPad)
        val expected = Resize.areaDownscale(argbPx, w, h, outW, outH)
        val actual = Resize.areaDownscaleRgba(buf, w, h, w * 4 + rowPad, 4, outW, outH)
        assertContentEquals(expected, actual)
    }

    @Test fun rgbaDownscaleMatchesArgb64x48to16x12() = assertRgbaMatchesArgb(64, 48, 16, 12, seed = 1, rowPad = 12)

    @Test fun rgbaDownscaleMatchesArgb1280x720to224x224() =
        assertRgbaMatchesArgb(1280, 720, 224, 224, seed = 2, rowPad = 64)

    private fun maxChannelDiff(a: IntArray, b: IntArray): Int {
        assertEquals(a.size, b.size)
        var m = 0
        for (i in a.indices) for (sh in intArrayOf(16, 8, 0)) {
            m = maxOf(m, kotlin.math.abs(((a[i] shr sh) and 0xFF) - ((b[i] shr sh) and 0xFF)))
        }
        return m
    }

    @Test fun shrinkThenRotateMatchesRotateThenShrink() {
        val w = 64
        val h = 48
        val iw = 21
        val ih = 17
        val (px, _) = randomFrame(w, h, seed = 3, rowPad = 0)
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val swap = deg == 90 || deg == 270
            val rw = if (swap) h else w
            val rh = if (swap) w else h
            val rotateFirst = Resize.areaDownscale(Rotate90.rotate(px, w, h, deg), rw, rh, iw, ih)
            val sw = if (swap) ih else iw
            val sh = if (swap) iw else ih
            val shrinkFirst = Rotate90.rotate(Resize.areaDownscale(px, w, h, sw, sh), sw, sh, deg)
            val d = maxChannelDiff(rotateFirst, shrinkFirst)
            assertTrue(d <= 1, "deg=$deg max channel diff $d > 1")
        }
    }

    /**
     * Тот же ARGB-кадр как RGBA-плоскость с произвольными шагами. [shortLastRow] — буфер кончается
     * на последнем пикселе (последняя строка короче rowStride), [direct] — allocateDirect.
     */
    private fun rgbaPlane(
        argbPx: IntArray, w: Int, h: Int, rowStride: Int, pixelStride: Int,
        direct: Boolean = false, shortLastRow: Boolean = false, seed: Int = 99,
    ): ByteBuffer {
        val rnd = Random(seed)
        val size = if (shortLastRow) (h - 1) * rowStride + (w - 1) * pixelStride + 4 else rowStride * h
        val bytes = ByteArray(size) { rnd.nextInt(256).toByte() }
        for (y in 0 until h) for (x in 0 until w) {
            val p = argbPx[y * w + x]
            val o = y * rowStride + x * pixelStride
            bytes[o] = ((p shr 16) and 0xFF).toByte()
            bytes[o + 1] = ((p shr 8) and 0xFF).toByte()
            bytes[o + 2] = (p and 0xFF).toByte()
            bytes[o + 3] = 0xFF.toByte()
        }
        return if (direct) ByteBuffer.allocateDirect(size).put(bytes).also { it.position(0) } else ByteBuffer.wrap(bytes)
    }

    @Test fun rgbaPixelStride8MatchesArgb() {
        val (px, _) = randomFrame(40, 30, seed = 4, rowPad = 0)
        val buf = rgbaPlane(px, 40, 30, rowStride = 40 * 8 + 16, pixelStride = 8)
        assertContentEquals(Resize.areaDownscale(px, 40, 30, 13, 11), Resize.areaDownscaleRgba(buf, 40, 30, 336, 8, 13, 11))
    }

    @Test fun rgbaDirectBufferMatchesArgb() {
        val (px, _) = randomFrame(40, 30, seed = 5, rowPad = 0)
        val buf = rgbaPlane(px, 40, 30, rowStride = 176, pixelStride = 4, direct = true)
        assertTrue(buf.isDirect)
        assertContentEquals(Resize.areaDownscale(px, 40, 30, 13, 11), Resize.areaDownscaleRgba(buf, 40, 30, 176, 4, 13, 11))
    }

    @Test fun rgbaShortLastRowMatchesArgbAndPositionUnchanged() {
        val (px, _) = randomFrame(40, 30, seed = 6, rowPad = 0)
        val buf = rgbaPlane(px, 40, 30, rowStride = 192, pixelStride = 4, shortLastRow = true)
        assertEquals(29 * 192 + 160, buf.limit())
        val expected = Resize.areaDownscale(px, 40, 30, 13, 11)
        assertContentEquals(expected, Resize.areaDownscaleRgba(buf, 40, 30, 192, 4, 13, 11))
        assertEquals(0, buf.position())
        assertContentEquals(expected, Resize.uprightDownscaleRgba(buf, 40, 30, 192, 4, 0, 13, 11))
        assertEquals(0, buf.position())
        assertEquals(buf.capacity(), buf.limit())
    }

    @Test fun rgbaRequires() {
        val (px, _) = randomFrame(8, 6, seed = 7, rowPad = 0)
        val buf = rgbaPlane(px, 8, 6, rowStride = 32, pixelStride = 4)
        assertFailsWith<IllegalArgumentException> { Resize.areaDownscaleRgba(buf, 8, 6, 32, 4, 9, 6) } // upscale W
        assertFailsWith<IllegalArgumentException> { Resize.areaDownscaleRgba(buf, 8, 6, 32, 4, 8, 7) } // upscale H
        assertFailsWith<IllegalArgumentException> { Resize.areaDownscaleRgba(buf, 8, 6, 31, 4, 4, 3) } // rowStride < w*4
        assertFailsWith<IllegalArgumentException> { Resize.areaDownscaleRgba(buf, 8, 6, 32, 3, 4, 3) } // pixelStride < 4
        buf.position(4)
        assertFailsWith<IllegalArgumentException> { Resize.areaDownscaleRgba(buf, 8, 6, 32, 4, 4, 3) } // position != 0
        assertFailsWith<IllegalArgumentException> { Resize.uprightDownscaleRgba(buf, 8, 6, 32, 4, 0, 4, 3) }
        buf.position(0)
        assertFailsWith<IllegalArgumentException> { Resize.uprightDownscaleRgba(buf, 8, 6, 32, 4, 45, 4, 3) }
    }

    @Test fun uprightMatchesRotateThenShrink1280x720() {
        val w = 1280
        val h = 720
        val (px, buf) = randomFrame(w, h, seed = 8, rowPad = 64)
        val scratch = ByteArray(buf.limit())
        for ((iw, ih) in listOf(320 to 240, 224 to 224)) {
            for (deg in intArrayOf(0, 90, 180, 270)) {
                val swap = deg == 90 || deg == 270
                val rw = if (swap) h else w
                val rh = if (swap) w else h
                val expected = Resize.areaDownscale(Rotate90.rotate(px, w, h, deg), rw, rh, iw, ih)
                val actual = Resize.uprightDownscaleRgba(buf, w, h, w * 4 + 64, 4, deg, iw, ih, scratch)
                val d = maxChannelDiff(expected, actual)
                assertTrue(d <= 1, "${iw}x$ih deg=$deg max channel diff $d > 1")
                assertEquals(0, buf.position())
            }
        }
    }

    @Test fun uprightWithSmallOrNullScratchStillWorks() {
        val (px, buf) = randomFrame(40, 30, seed = 9, rowPad = 8)
        val expected = Resize.areaDownscale(px, 40, 30, 13, 11)
        assertContentEquals(expected, Resize.uprightDownscaleRgba(buf, 40, 30, 168, 4, 0, 13, 11, ByteArray(10)))
        assertContentEquals(expected, Resize.uprightDownscaleRgba(buf, 40, 30, 168, 4, 0, 13, 11, null))
    }
}
