package io.visnav.app

import android.graphics.BitmapFactory
import io.visnav.core.Preprocess
import io.visnav.core.Vectors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ParityCheck {
    /** Если в dir/parity лежат input.png и expected.f32 (vpr-m1 make-parity), считает косинус и пишет out. */
    fun runIfPresent(dir: File, embedder: OrtEmbedder, out: File): Float? {
        val png = File(dir, "parity/input.png")
        val exp = File(dir, "parity/expected.f32")
        if (!png.isFile || !exp.isFile) return null
        val bmp = BitmapFactory.decodeFile(png.absolutePath)
            ?: error("не удалось декодировать ${png.absolutePath}")
        check(bmp.width == embedder.inputW && bmp.height == embedder.inputH) { "parity image size mismatch" }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val got = embedder.embed(Preprocess.argbToRgb(px), bmp.width, bmp.height)
        val fb = ByteBuffer.wrap(exp.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val expected = FloatArray(fb.remaining()).also { fb.get(it) }
        val cos = Vectors.cosine(got, expected)
        out.parentFile?.mkdirs()
        out.writeText("{\"cosine\":$cos,\"dim\":${got.size}}\n")
        return cos
    }
}
