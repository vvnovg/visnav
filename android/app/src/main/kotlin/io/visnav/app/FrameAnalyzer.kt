package io.visnav.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.visnav.core.Preprocess
import io.visnav.core.Resize

/**
 * Пропускает кадры чаще intervalMs; остальные уменьшает до входа модели прямо из RGBA-плоскости,
 * поворачивает уже маленькое изображение и отдаёт в onFrame. Ожидает OUTPUT_IMAGE_FORMAT_RGBA_8888.
 */
class FrameAnalyzer(
    intervalMs: Long,
    private val inputW: Int,
    private val inputH: Int,
) : ImageAnalysis.Analyzer {
    /** Минимальный интервал между обрабатываемыми кадрами; меняется на ходу (регулятор частоты кадров). */
    @Volatile var intervalMs: Long = intervalMs

    /** captureTsNs — ImageProxy.imageInfo.timestamp (нс, монотонные часы камеры) в момент кадра. */
    @Volatile var onFrame: ((tMs: Long, rgb: ByteArray, preMs: Double, captureTsNs: Long) -> Unit)? = null

    /** Вызывается на любую необработанную ошибку внутри analyze() — кадр уже закрыт к этому моменту. */
    @Volatile var onError: ((Throwable) -> Unit)? = null

    // Размер последнего кадра после поворота (до уменьшения) — «Кадр: WxH» на экране и в заголовке сессии.
    @Volatile var lastFrameW: Int = 0
    @Volatile var lastFrameH: Int = 0

    private var lastMs = 0L

    // Переиспользуемый буфер для копии RGBA-плоскости (только поток анализа).
    private var scratch = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        val handler = onFrame
        // Throttle by the monotonic clock (immune to wall-clock jumps from NTP/GPS time sync);
        // tMs below stays on the wall clock since it's the record's timestamp, not a duration.
        val nowElapsed = SystemClock.elapsedRealtime()
        if (handler == null || nowElapsed - lastMs < intervalMs) { image.close(); return }
        lastMs = nowElapsed
        val captureTsNs = image.imageInfo.timestamp
        try {
            val now = System.currentTimeMillis()
            val t0 = System.nanoTime()
            val rgb = try {
                val rot = image.imageInfo.rotationDegrees
                val swap = rot == 90 || rot == 270
                val uprightW = if (swap) image.height else image.width
                val uprightH = if (swap) image.width else image.height
                lastFrameW = uprightW
                lastFrameH = uprightH
                val downscaled = if (uprightW >= inputW && uprightH >= inputH) {
                    // Уменьшение прямо из RGBA-плоскости и поворот уже маленького изображения —
                    // без полнокадрового Bitmap; плоскость копируется одним блоком в scratch.
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    if (scratch.size < buf.limit()) scratch = ByteArray(buf.limit())
                    Resize.uprightDownscaleRgba(
                        buf, image.width, image.height, plane.rowStride, plane.pixelStride,
                        rot, inputW, inputH, scratch,
                    )
                } else {
                    upscaleViaBitmap(image, rot)
                }
                Preprocess.argbToRgb(downscaled)
            } finally {
                image.close()
            }
            handler(now, rgb, (System.nanoTime() - t0) / 1e6, captureTsNs)
        } catch (t: Throwable) {
            // Кадр уже закрыт (finally выше отработал до того, как исключение долетело сюда) —
            // один плохой кадр на исполнителе анализа не должен ронять его целиком.
            onError?.invoke(t)
        }
    }

    /**
     * Кадр меньше входа модели (не должно происходить в норме) — только для этого случая
     * используем интерполирующее увеличение Bitmap вместо area-average уменьшения.
     */
    private fun upscaleViaBitmap(image: ImageProxy, rot: Int): IntArray {
        var bmp: Bitmap? = null
        var upright: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val b = image.toBitmap()
            bmp = b
            val u = if (rot == 0) b else
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            upright = u
            val s = Bitmap.createScaledBitmap(u, inputW, inputH, true)
            scaled = s
            val px = IntArray(inputW * inputH)
            s.getPixels(px, 0, inputW, 0, 0, inputW, inputH)
            return px
        } finally {
            // Не освобождать один и тот же Bitmap дважды: createBitmap/createScaledBitmap могут
            // вернуть тот же экземпляр, если преобразование не требовалось.
            scaled?.takeIf { it !== upright && it !== bmp }?.recycle()
            upright?.takeIf { it !== bmp }?.recycle()
            bmp?.recycle()
        }
    }
}
