package io.visnav.core

/** Пиксели Bitmap (ARGB_8888 как Int) → плотный RGB uint8, вход ONNX-модели "image" [1, H, W, 3]. */
object Preprocess {
    fun argbToRgb(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[3 * i] = (p shr 16 and 0xFF).toByte()
            out[3 * i + 1] = (p shr 8 and 0xFF).toByte()
            out[3 * i + 2] = (p and 0xFF).toByte()
        }
        return out
    }
}
