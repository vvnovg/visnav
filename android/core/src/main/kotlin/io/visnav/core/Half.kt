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

    /** float → IEEE 754 binary16, округление к ближайшему чётному (как numpy.float16). */
    fun fromFloat(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        var mant = bits and 0x7FFFFF
        if (exp == 0xFF) return (sign or 0x7C00 or (if (mant != 0) 0x200 else 0)).toShort()
        val e = exp - 127 + 15
        if (e >= 0x1F) return (sign or 0x7C00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            mant = mant or 0x800000
            val shift = 14 - e
            var half = mant ushr shift
            val rem = mant and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (rem > halfway || (rem == halfway && (half and 1) == 1)) half++
            return (sign or half).toShort()
        }
        var half = (e shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        if (rem > 0x1000 || (rem == 0x1000 && (half and 1) == 1)) half++ // перенос в экспоненту корректен
        return (sign or half).toShort()
    }

    val LUT: FloatArray by lazy { FloatArray(65536) { toFloat(it.toShort()) } }
}
