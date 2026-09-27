package io.visnav.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** refpack v1: заголовок 16 байт, затем lats f64, lons f64, headings f32, descriptors f16 (little-endian). */
class RefPack(
    val count: Int,
    val dim: Int,
    val lats: DoubleArray,
    val lons: DoubleArray,
    val headings: FloatArray,
    val descriptors: ShortArray,
) {
    companion object {
        const val MAGIC = 0x50524E56 // "VNRP" как little-endian int
        private const val HEADER_SIZE = 16

        fun parse(buf: ByteBuffer): RefPack {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val total = b.remaining().toLong()
            require(total >= HEADER_SIZE) { "refpack too short" }
            require(b.int == MAGIC) { "not a refpack (bad magic)" }
            val version = b.short.toInt() and 0xFFFF
            val dtype = b.short.toInt() and 0xFFFF
            require(version == 1 && dtype == 1) { "unsupported refpack version=$version dtype=$dtype" }
            val count = b.int
            val dim = b.int
            require(count >= 0 && dim > 0) { "bad header count=$count dim=$dim" }
            val expected = HEADER_SIZE + count.toLong() * 20 + count.toLong() * dim * 2
            require(total == expected) { "refpack size $total != expected $expected" }

            val lats = DoubleArray(count).also { b.asDoubleBuffer().get(it) }
            b.position(b.position() + 8 * count)
            val lons = DoubleArray(count).also { b.asDoubleBuffer().get(it) }
            b.position(b.position() + 8 * count)
            val headings = FloatArray(count).also { b.asFloatBuffer().get(it) }
            b.position(b.position() + 4 * count)
            val desc = ShortArray(count * dim).also { b.asShortBuffer().get(it) }
            return RefPack(count, dim, lats, lons, headings, desc)
        }
    }
}

@Serializable
data class RefPackMeta(
    val format: String,
    val model: String,
    @SerialName("created_at") val createdAt: String,
    val count: Int,
    val dim: Int,
    @SerialName("input_h") val inputH: Int,
    @SerialName("input_w") val inputW: Int,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): RefPackMeta = json.decodeFromString(serializer(), text)
    }
}
