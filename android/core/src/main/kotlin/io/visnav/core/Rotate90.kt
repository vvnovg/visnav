package io.visnav.core

/** Поворот ARGB-изображения на кратный 90° угол по часовой стрелке — как Matrix.postRotate. */
object Rotate90 {
    /**
     * [degrees] ∈ {0, 90, 180, 270}. Для 90/270 результат имеет размер h×w (ширина h).
     * 90 по часовой: (x, y) → (h−1−y, x). Для 0 возвращается копия.
     */
    fun rotate(px: IntArray, w: Int, h: Int, degrees: Int): IntArray {
        require(px.size == w * h) { "px.size=${px.size} != $w*$h" }
        val out = IntArray(w * h)
        when (degrees) {
            0 -> px.copyInto(out)
            90 -> for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = px[y * w + x]
            180 -> for (y in 0 until h) for (x in 0 until w) out[(h - 1 - y) * w + (w - 1 - x)] = px[y * w + x]
            270 -> for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = px[y * w + x]
            else -> throw IllegalArgumentException("degrees=$degrees must be 0, 90, 180 or 270")
        }
        return out
    }
}
