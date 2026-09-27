package io.visnav.core

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** .desc v1: "VNDS", u16 version=1, u16 dtype=1 (f16), u32 dim; затем записи i64 t_ms + f16[dim]. */
class DescriptorLogWriter(file: File, val dim: Int) : Closeable {
    private val out = file.outputStream().buffered()
    private val record = ByteBuffer.allocate(8 + 2 * dim).order(ByteOrder.LITTLE_ENDIAN)
    private var count = 0

    init {
        require(dim > 0) { "dim must be > 0" }
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.put("VNDS".toByteArray()).putShort(1).putShort(1).putInt(dim)
        out.write(header.array())
        out.flush()
    }

    fun write(tMs: Long, desc: FloatArray) {
        require(desc.size == dim) { "descriptor size ${desc.size} != $dim" }
        record.clear()
        record.putLong(tMs)
        for (v in desc) record.putShort(Half.fromFloat(v))
        out.write(record.array())
        if (++count % 20 == 0) out.flush()
    }

    override fun close() { out.flush(); out.close() }
}

class DescriptorLog(val dim: Int, val times: LongArray, val descriptors: ShortArray) {
    private val byTime: Map<Long, Int> = times.withIndex().associate { (i, t) -> t to i }

    fun indexOf(tMs: Long): Int = byTime[tMs] ?: -1

    fun descriptor(i: Int): FloatArray = FloatArray(dim) { Half.LUT[descriptors[i * dim + it].toInt() and 0xFFFF] }

    companion object {
        fun read(file: File): DescriptorLog {
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            require(b.remaining() >= 12) { "desc file too short" }
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic) == "VNDS") { "not a VNDS file" }
            val version = b.short.toInt(); val dtype = b.short.toInt(); val dim = b.int
            require(version == 1 && dtype == 1 && dim > 0) { "unsupported desc file v=$version dtype=$dtype dim=$dim" }
            val recSize = 8 + 2 * dim
            val n = b.remaining() / recSize // обрезанный хвост отбрасывается
            val times = LongArray(n)
            val desc = ShortArray(n * dim)
            for (i in 0 until n) {
                times[i] = b.long
                for (j in 0 until dim) desc[i * dim + j] = b.short
            }
            return DescriptorLog(dim, times, desc)
        }
    }
}
