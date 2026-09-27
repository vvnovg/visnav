package io.visnav.core

import kotlin.math.sqrt

object Vectors {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "size mismatch ${a.size} != ${b.size}" }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }
}
