package io.visnav.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.visnav.core.Preprocess
import io.visnav.core.Resize

/** Пропускает кадры чаще intervalMs; остальные поворачивает, уменьшает до входа модели и отдаёт в onFrame. */
class FrameAnalyzer(
    private val intervalMs: Long,
    private val inputW: Int,
    private val inputH: Int,
) : ImageAnalysis.Analyzer {
    /** captureTsNs — ImageProxy.imageInfo.timestamp (нс, монотонные часы камеры) в момент кадра. */
    @Volatile var onFrame: ((tMs: Long, rgb: ByteArray, preMs: Double, captureTsNs: Long) -> Unit)? = null

    /** Вызывается на любую необработанную ошибку внутри analyze() — кадр уже закрыт к этому моменту. */
    @Volatile var onError: ((Throwable) -> Unit)? = null

    // Размер последнего кадра после поворота (до уменьшения) — «Кадр: WxH» на экране и в заголовке сессии.
    @Volatile var lastFrameW: Int = 0
    @Volatile var lastFrameH: Int = 0

    private var lastMs = 0L

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
            var bmp: Bitmap? = null
            var upright: Bitmap? = null
            var scaled: Bitmap? = null
            val rgb = try {
                val b = image.toBitmap()
                bmp = b
                val rot = image.imageInfo.rotationDegrees
                val u = if (rot == 0) b else
                    Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                upright = u
                lastFrameW = u.width
                lastFrameH = u.height
                val downscaled: IntArray
                if (u.width >= inputW && u.height >= inputH) {
                    val px = IntArray(u.width * u.height)
                    u.getPixels(px, 0, u.width, 0, 0, u.width, u.height)
                    downscaled = Resize.areaDownscale(px, u.width, u.height, inputW, inputH)
                } else {
                    // Кадр меньше входа модели (не должно происходить в норме) — только для этого случая
                    // используем интерполирующее увеличение Bitmap вместо area-average уменьшения.
                    val s = Bitmap.createScaledBitmap(u, inputW, inputH, true)
                    scaled = s
                    val px = IntArray(inputW * inputH)
                    s.getPixels(px, 0, inputW, 0, 0, inputW, inputH)
                    downscaled = px
                }
                Preprocess.argbToRgb(downscaled)
            } finally {
                image.close()
                // Не освобождать один и тот же Bitmap дважды: createBitmap/createScaledBitmap могут
                // вернуть тот же экземпляр, если преобразование не требовалось.
                scaled?.takeIf { it !== upright && it !== bmp }?.recycle()
                upright?.takeIf { it !== bmp }?.recycle()
                bmp?.recycle()
            }
            handler(now, rgb, (System.nanoTime() - t0) / 1e6, captureTsNs)
        } catch (t: Throwable) {
            // Кадр уже закрыт (finally выше отработал до того, как исключение долетело сюда) —
            // один плохой кадр на исполнителе анализа не должен ронять его целиком.
            onError?.invoke(t)
        }
    }
}
