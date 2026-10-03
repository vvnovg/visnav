package io.visnav.core

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Запрет/предписание поворота: переход с ребра [fromEdge] через узел [via] на ребро [toEdge].
 * Контракт: [only] = false — запрещённый поворот (no_*), этот переход исключается; [only] = true — предписанный
 * (only_*), из пары (fromEdge, via) разрешены только указанные выходы. Несколько строк `only` с одной парой
 * (fromEdge, via) образуют ОБЪЕДИНЕНИЕ разрешённых выходов. Читатель только хранит строки; применяет их маршрутизатор.
 */
data class TurnRestriction(val fromEdge: Int, val via: Int, val toEdge: Int, val only: Boolean)

/**
 * roadpack v1/v2 («VNRD»): дорожный граф OSM. Заголовок 16 байт (magic, version u16, reserved u16, nodeCount u32,
 * edgeCount u32), затем lats f64[n], lons f64[n], way i64[m], from i32[m], to i32[m], flags u8[m], cls u8[m]
 * (little-endian). v2 дополнительно: speedKmh u8[m] (0 = по классу), nameIdx i32[m] (-1 = без названия),
 nameCount u32 + names (u16 длина + UTF-8), restrictionCount u32 + по 13 байт (from i32, via i32, to i32, kind u8: 2 = only).
 Ребро — прямой отрезок между соседними узлами OSM-линии; по односторонней — только from → to.
 */
class RoadPack(
    val lats: DoubleArray, val lons: DoubleArray,
    val way: LongArray, val from: IntArray, val to: IntArray,
    val flags: ByteArray, val cls: ByteArray,
    val speedKmh: ByteArray = ByteArray(from.size) { RoadClass.defaultSpeedKmh(cls[it].toInt() and 0xFF).toByte() },
    val nameIdx: IntArray = IntArray(from.size) { -1 },
    val names: List<String> = emptyList(),
    val restrictions: List<TurnRestriction> = emptyList(),
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
        require(speedKmh.size == m && nameIdx.size == m) { "speed/name arrays size mismatch" }
        for (k in 0 until m) require(nameIdx[k] in -1 until names.size) { "edge $k: name index out of range" }
        for (r in restrictions) {
            require(r.fromEdge in 0 until m && r.toEdge in 0 until m && r.via in 0 until nodeCount) {
                "bad restriction $r"
            }
        }
    }

    fun oneway(edge: Int): Boolean = flags[edge].toInt() and FLAG_ONEWAY != 0
    fun tunnel(edge: Int): Boolean = flags[edge].toInt() and FLAG_TUNNEL != 0
    fun roadClass(edge: Int): Int = cls[edge].toInt() and 0xFF
    fun roundabout(edge: Int): Boolean = flags[edge].toInt() and FLAG_ROUNDABOUT != 0
    fun name(edge: Int): String? = nameIdx[edge].let { if (it < 0) null else names[it] }
    fun speedMps(edge: Int): Double {
        val v = speedKmh[edge].toInt() and 0xFF
        return (if (v == 0) RoadClass.defaultSpeedKmh(roadClass(edge)) else v) / 3.6
    }

    companion object {
        const val MAGIC = 0x44524E56 // "VNRD" как little-endian int
        const val FLAG_ONEWAY = 1
        const val FLAG_TUNNEL = 2
        const val FLAG_BRIDGE = 4
        const val FLAG_ROUNDABOUT = 8
        private const val HEADER_SIZE = 16

        fun parse(buf: ByteBuffer): RoadPack = try {
            parseUnchecked(buf)
        } catch (e: BufferUnderflowException) {
            throw IllegalArgumentException("roadpack truncated", e)
        }

        private fun parseUnchecked(buf: ByteBuffer): RoadPack {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val total = b.remaining().toLong()
            require(total >= HEADER_SIZE) { "roadpack too short" }
            require(b.int == MAGIC) { "not a roadpack (bad magic)" }
            val version = b.short.toInt() and 0xFFFF
            b.short // reserved
            require(version == 1 || version == 2) { "unsupported roadpack version=$version" }
            val n = b.int
            val m = b.int
            require(n >= 0 && m >= 0) { "bad header nodes=$n edges=$m" }
            val expected = HEADER_SIZE + 16L * n + 18L * m
            if (version == 1) require(total == expected) { "roadpack size $total != expected $expected" }
            else require(total >= expected + 5L * m + 8) { "roadpack v2 too short: $total" }
            val lats = DoubleArray(n).also { b.asDoubleBuffer().get(it) }; b.position(b.position() + 8 * n)
            val lons = DoubleArray(n).also { b.asDoubleBuffer().get(it) }; b.position(b.position() + 8 * n)
            val way = LongArray(m).also { b.asLongBuffer().get(it) }; b.position(b.position() + 8 * m)
            val from = IntArray(m).also { b.asIntBuffer().get(it) }; b.position(b.position() + 4 * m)
            val to = IntArray(m).also { b.asIntBuffer().get(it) }; b.position(b.position() + 4 * m)
            val flags = ByteArray(m).also { b.get(it) }
            val cls = ByteArray(m).also { b.get(it) }
            var speed: ByteArray? = null; var nameIdx: IntArray? = null
            var names: List<String> = emptyList(); var restrictions: List<TurnRestriction> = emptyList()
            if (version == 2) {
                speed = ByteArray(m).also { b.get(it) }
                nameIdx = IntArray(m).also { b.asIntBuffer().get(it) }; b.position(b.position() + 4 * m)
                val nc = b.int
                require(nc >= 0) { "bad name count" }
                names = List(nc) {
                    val len = b.short.toInt() and 0xFFFF
                    require(b.remaining() >= len) { "roadpack truncated in names" }
                    ByteArray(len).also { b.get(it) }.toString(Charsets.UTF_8)
                }
                val rc = b.int
                require(rc >= 0 && b.remaining().toLong() == 13L * rc) {
                    "roadpack restriction table size mismatch (${b.remaining()} trailing bytes for $rc entries)"
                }
                restrictions = List(rc) { TurnRestriction(b.int, b.int, b.int, (b.get().toInt() and 0xFF) == 2) }
            }
            return RoadPack(lats, lons, way, from, to, flags, cls,
                speed ?: ByteArray(m) { RoadClass.defaultSpeedKmh(cls[it].toInt() and 0xFF).toByte() },
                nameIdx ?: IntArray(m) { -1 }, names, restrictions)
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

    /** Скорость для оценки времени в пути по классу, км/ч (общая с vpr_bench.roadpack.DEFAULT_SPEED_KMH). */
    fun defaultSpeedKmh(cls: Int): Int = when (cls) {
        MOTORWAY -> 90; TRUNK -> 70; PRIMARY -> 60; SECONDARY -> 50; TERTIARY -> 40
        UNCLASSIFIED -> 30; RESIDENTIAL -> 20; LIVING_STREET, SERVICE -> 10; else -> 20
    }

    /** Сигма поперечного псевдоизмерения — примерно полуширина проезжей части. */
    fun lateralSigmaM(cls: Int): Double = when (cls) {
        MOTORWAY, TRUNK -> 7.0
        PRIMARY -> 6.0
        SECONDARY -> 5.0
        else -> 4.0
    }
}
