package io.visnav.core

import java.nio.ByteBuffer
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

        return areaCore(srcW, srcH, dstW, dstH) { x, y -> src[y * srcW + x] }
    }

    /**
     * То же area-усреднение, что [areaDownscale], но вход — байты RGBA_8888 (порядок R,G,B,A) с
     * шагом строки [rowStride] и шагом пикселя [pixelStride], как `ImageProxy.planes[0]` у CameraX.
     * Индексы считаются от индекса 0 буфера, поэтому позиция буфера должна быть 0; она не меняется.
     * Последняя строка может быть короче [rowStride] (буфер кончается на последнем пикселе).
     * Результат — ARGB, альфа 0xFF.
     */
    fun areaDownscaleRgba(
        buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, outW: Int, outH: Int,
    ): IntArray {
        checkRgba(buf, w, h, rowStride, pixelStride, outW, outH)
        return areaCore(w, h, outW, outH) { x, y ->
            val o = y * rowStride + x * pixelStride
            ((buf.get(o).toInt() and 0xFF) shl 16) or
                ((buf.get(o + 1).toInt() and 0xFF) shl 8) or
                (buf.get(o + 2).toInt() and 0xFF)
        }
    }

    /**
     * Кадр камеры (RGBA_8888, до поворота, w×h) → вход модели [inputW]×[inputH] в вертикальной
     * ориентации: уменьшение в размер «до поворота» (для 90/270 стороны меняются местами), затем
     * [Rotate90.rotate] уже маленького изображения (при 0 поворот не делается).
     *
     * Плоскость копируется одним блоком (`limit` байт) в [scratch], если его размер ≥ `buf.limit()`,
     * иначе в новый массив; дальше чтение идёт из массива. Позиция буфера должна быть 0 и не меняется.
     */
    fun uprightDownscaleRgba(
        buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int,
        rotationDegrees: Int, inputW: Int, inputH: Int, scratch: ByteArray? = null,
    ): IntArray {
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            "rotationDegrees=$rotationDegrees must be 0, 90, 180 or 270"
        }
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val sw = if (swap) inputH else inputW
        val sh = if (swap) inputW else inputH
        checkRgba(buf, w, h, rowStride, pixelStride, sw, sh)
        val n = buf.limit()
        val bytes = if (scratch != null && scratch.size >= n) scratch else ByteArray(n)
        buf.duplicate().apply { position(0) }.get(bytes, 0, n)
        val small = areaCore(w, h, sw, sh) { x, y ->
            val o = y * rowStride + x * pixelStride
            ((bytes[o].toInt() and 0xFF) shl 16) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                (bytes[o + 2].toInt() and 0xFF)
        }
        return if (rotationDegrees == 0) small else Rotate90.rotate(small, sw, sh, rotationDegrees)
    }

    private fun checkRgba(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, outW: Int, outH: Int) {
        require(outW in 1..w) { "outW=$outW must be in 1..$w (downscale only)" }
        require(outH in 1..h) { "outH=$outH must be in 1..$h (downscale only)" }
        require(pixelStride >= 4) { "pixelStride=$pixelStride must be >= 4" }
        require(rowStride >= w * pixelStride) { "rowStride=$rowStride must be >= ${w * pixelStride}" }
        require(buf.position() == 0) { "buffer position=${buf.position()} must be 0" }
        val need = (h - 1).toLong() * rowStride + (w - 1).toLong() * pixelStride + 4
        require(buf.limit() >= need) { "buffer limit=${buf.limit()} < $need needed for ${w}x$h" }
    }

    /** Общее ядро: [pixel] возвращает пиксель источника (x, y) как 0x??RRGGBB. */
    private inline fun areaCore(srcW: Int, srcH: Int, dstW: Int, dstH: Int, pixel: (Int, Int) -> Int): IntArray {
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
                        val p = pixel(xx, yy)
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
