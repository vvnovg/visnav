package io.visnav.core

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Понижение разрешения ARGB-изображения областным усреднением — как cv2.INTER_AREA. */
object Resize {
    /**
     * Каждый пиксель результата — взвешенное по площади среднее пикселей источника, которые
     * покрывает его проекция (дробное покрытие на границах). Альфа результата всегда 0xFF.
     * Только уменьшение: dstW <= srcW и dstH <= srcH; при равных размерах — копия.
     */
    fun areaDownscale(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        require(dstW in 1..srcW) { "dstW=$dstW must be in 1..$srcW (downscale only)" }
        require(dstH in 1..srcH) { "dstH=$dstH must be in 1..$srcH (downscale only)" }
        if (dstW == srcW && dstH == srcH) return src.copyOf()

        val sx = srcW.toDouble() / dstW
        val sy = srcH.toDouble() / dstH
        val out = IntArray(dstW * dstH)

        for (dy in 0 until dstH) {
            val y0 = dy * sy
            val y1 = (dy + 1) * sy
            val yFrom = y0.toInt()
            val yTo = min(srcH - 1, ceil(y1).toInt() - 1)
            for (dx in 0 until dstW) {
                val x0 = dx * sx
                val x1 = (dx + 1) * sx
                val xFrom = x0.toInt()
                val xTo = min(srcW - 1, ceil(x1).toInt() - 1)

                var rSum = 0.0
                var gSum = 0.0
                var bSum = 0.0
                var wSum = 0.0
                for (yy in yFrom..yTo) {
                    val yWeight = overlap(yy.toDouble(), yy + 1.0, y0, y1)
                    if (yWeight <= 0.0) continue
                    for (xx in xFrom..xTo) {
                        val xWeight = overlap(xx.toDouble(), xx + 1.0, x0, x1)
                        if (xWeight <= 0.0) continue
                        val w = xWeight * yWeight
                        val p = src[yy * srcW + xx]
                        rSum += w * ((p shr 16) and 0xFF)
                        gSum += w * ((p shr 8) and 0xFF)
                        bSum += w * (p and 0xFF)
                        wSum += w
                    }
                }
                val r = (rSum / wSum).roundToInt().coerceIn(0, 255)
                val g = (gSum / wSum).roundToInt().coerceIn(0, 255)
                val b = (bSum / wSum).roundToInt().coerceIn(0, 255)
                out[dy * dstW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double): Double =
        (min(a1, b1) - kotlin.math.max(a0, b0)).coerceAtLeast(0.0)
}
