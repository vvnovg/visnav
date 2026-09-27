package io.visnav.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Golden test for [Resize.areaDownscale] against OpenCV's cv2.resize(..., INTER_AREA), the
 * reference the PC-side pipeline uses (see vpr_bench.onnx_export.to_model_input /
 * vpr_bench.models.VprModel.preprocess). The fixture is a deterministic 12x9 -> 8x6 RGB case,
 * generated once on the PC side with:
 *
 * ```python
 * import numpy as np
 * import cv2
 * rng = np.random.default_rng(42)
 * src = rng.integers(0, 256, (9, 12, 3), dtype=np.uint8)  # H=9, W=12
 * dst = cv2.resize(src, (8, 6), interpolation=cv2.INTER_AREA)  # W=8, H=6
 * src.tofile("src_12x9.rgb")
 * dst.tofile("expected_8x6.rgb")
 * ```
 *
 * Both files are raw, packed RGB bytes (row-major, 3 bytes/pixel, no header). Since the two
 * implementations use different rounding for the fractional-coverage weighted average, we allow
 * up to +/-1 per channel rather than requiring an exact match.
 */
class ResizeGoldenTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/resize_golden/$name")) { name }.readBytes()

    private fun rgbBytesToArgb(bytes: ByteArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val r = bytes[i * 3].toInt() and 0xFF
            val g = bytes[i * 3 + 1].toInt() and 0xFF
            val b = bytes[i * 3 + 2].toInt() and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    @Test fun matchesOpenCvInterAreaWithinOnePerChannel() {
        val srcW = 12; val srcH = 9
        val dstW = 8; val dstH = 6
        val src = rgbBytesToArgb(resource("src_12x9.rgb"), srcW, srcH)
        val expected = rgbBytesToArgb(resource("expected_8x6.rgb"), dstW, dstH)

        val actual = Resize.areaDownscale(src, srcW, srcH, dstW, dstH)

        assertTrue(actual.size == expected.size)
        for (i in actual.indices) {
            val a = actual[i]; val e = expected[i]
            val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
            val er = (e shr 16) and 0xFF; val eg = (e shr 8) and 0xFF; val eb = e and 0xFF
            assertTrue(abs(ar - er) <= 1, "pixel $i red: actual=$ar expected=$er")
            assertTrue(abs(ag - eg) <= 1, "pixel $i green: actual=$ag expected=$eg")
            assertTrue(abs(ab - eb) <= 1, "pixel $i blue: actual=$ab expected=$eb")
        }
    }
}
