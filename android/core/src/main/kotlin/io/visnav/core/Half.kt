package io.visnav.core

/** IEEE 754 binary16 → float. Своя реализация: Float.float16ToFloat нет в Android-рантайме. */
object Half {
    fun toFloat(h: Short): Float {
        val bits = h.toInt() and 0xFFFF
        val sign = (bits ushr 15) shl 31
        val exp = (bits ushr 10) and 0x1F
        val mant = bits and 0x3FF
        val f = when {
            exp == 0 && mant == 0 -> sign
            exp == 0 -> {
                var e = -1
                var m = mant
                do { e++; m = m shl 1 } while (m and 0x400 == 0)
                sign or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
            }
            exp == 0x1F -> sign or (0xFF shl 23) or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(f)
    }

    val LUT: FloatArray by lazy { FloatArray(65536) { toFloat(it.toShort()) } }
}
