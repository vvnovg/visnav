package io.visnav.app

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.visnav.core.Preprocess

/** Пропускает кадры чаще intervalMs; остальные поворачивает, уменьшает до входа модели и отдаёт в onFrame. */
class FrameAnalyzer(
    private val intervalMs: Long,
    private val inputW: Int,
    private val inputH: Int,
) : ImageAnalysis.Analyzer {
    @Volatile var onFrame: ((tMs: Long, rgb: ByteArray, preMs: Double) -> Unit)? = null
    private var lastMs = 0L

    override fun analyze(image: ImageProxy) {
        val handler = onFrame
        val now = System.currentTimeMillis()
        if (handler == null || now - lastMs < intervalMs) { image.close(); return }
        lastMs = now
        val t0 = System.nanoTime()
        val rgb = try {
            val bmp = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val upright = if (rot == 0) bmp else
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            val scaled = Bitmap.createScaledBitmap(upright, inputW, inputH, true)
            val px = IntArray(inputW * inputH)
            scaled.getPixels(px, 0, inputW, 0, 0, inputW, inputH)
            Preprocess.argbToRgb(px)
        } finally {
            image.close()
        }
        handler(now, rgb, (System.nanoTime() - t0) / 1e6)
    }
}
