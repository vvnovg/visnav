package io.visnav.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * roadpack v1 («VNRD»): дорожный граф OSM. Заголовок 16 байт (magic, version u16, reserved u16, nodeCount u32,
 * edgeCount u32), затем lats f64[n], lons f64[n], way i64[m], from i32[m], to i32[m], flags u8[m], cls u8[m]
 * (little-endian). Ребро — прямой отрезок между соседними узлами OSM-линии; по односторонней — только from → to.
 */
class RoadPack(
    val lats: DoubleArray, val lons: DoubleArray,
    val way: LongArray, val from: IntArray, val to: IntArray,
    val flags: ByteArray, val cls: ByteArray,
) {
    val nodeCount: Int get() = lats.size
    val edgeCount: Int get() = from.size

    init {
        require(lons.size == lats.size) { "lats/lons size mismatch" }
        val m = from.size
        require(to.size == m && way.size == m && flags.size == m && cls.size == m) { "edge arrays size mismatch" }
        for (k in 0 until m) {
            require(from[k] in 0 until nodeCount && to[k] in 0 until nodeCount) { "edge $k: node index out of range" }
        }
    }

    fun oneway(edge: Int): Boolean = flags[edge].toInt() and FLAG_ONEWAY != 0
    fun tunnel(edge: Int): Boolean = flags[edge].toInt() and FLAG_TUNNEL != 0
    fun roadClass(edge: Int): Int = cls[edge].toInt() and 0xFF

    companion object {
        const val MAGIC = 0x44524E56 // "VNRD" как little-endian int
        const val FLAG_ONEWAY = 1
        const val FLAG_TUNNEL = 2
        const val FLAG_BRIDGE = 4
        private const val HEADER_SIZE = 16

        fun parse(buf: ByteBuffer): RoadPack {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val total = b.remaining().toLong()
            require(total >= HEADER_SIZE) { "roadpack too short" }
            require(b.int == MAGIC) { "not a roadpack (bad magic)" }
            val version = b.short.toInt() and 0xFFFF
            b.short // reserved
            require(version == 1) { "unsupported roadpack version=$version" }
            val n = b.int
            val m = b.int
            require(n >= 0 && m >= 0) { "bad header nodes=$n edges=$m" }
            val expected = HEADER_SIZE + 16L * n + 18L * m
            require(total == expected) { "roadpack size $total != expected $expected" }
            val lats = DoubleArray(n).also { b.asDoubleBuffer().get(it) }; b.position(b.position() + 8 * n)
            val lons = DoubleArray(n).also { b.asDoubleBuffer().get(it) }; b.position(b.position() + 8 * n)
            val way = LongArray(m).also { b.asLongBuffer().get(it) }; b.position(b.position() + 8 * m)
            val from = IntArray(m).also { b.asIntBuffer().get(it) }; b.position(b.position() + 4 * m)
            val to = IntArray(m).also { b.asIntBuffer().get(it) }; b.position(b.position() + 4 * m)
            val flags = ByteArray(m).also { b.get(it) }
            val cls = ByteArray(m).also { b.get(it) }
            return RoadPack(lats, lons, way, from, to, flags, cls)
        }
    }
}

@Serializable
data class RoadPackMeta(
    val format: String,
    @SerialName("created_at") val createdAt: String,
    val source: String,
    @SerialName("node_count") val nodeCount: Int,
    @SerialName("edge_count") val edgeCount: Int,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): RoadPackMeta = json.decodeFromString(serializer(), text)
    }
}

/** Коды классов дорог (общие с vpr_bench.osmgraph.ROAD_CLASS). */
object RoadClass {
    const val MOTORWAY = 1
    const val TRUNK = 2
    const val PRIMARY = 3
    const val SECONDARY = 4
    const val TERTIARY = 5
    const val UNCLASSIFIED = 6
    const val RESIDENTIAL = 7
    const val LIVING_STREET = 8
    const val SERVICE = 9

    /** Сигма поперечного псевдоизмерения — примерно полуширина проезжей части. */
    fun lateralSigmaM(cls: Int): Double = when (cls) {
        MOTORWAY, TRUNK -> 7.0
        PRIMARY -> 6.0
        SECONDARY -> 5.0
        else -> 4.0
    }
}
