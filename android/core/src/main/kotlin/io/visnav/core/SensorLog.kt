package io.visnav.core

import java.io.Closeable
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** События датчиков; t — настенное время телефона, мс. Формат — контракт .sensors.jsonl v1 (план M2a). */
sealed interface SensorEvent { val tMs: Double }
data class GyroEvent(override val tMs: Double, val x: Float, val y: Float, val z: Float) : SensorEvent
data class AccelEvent(override val tMs: Double, val x: Float, val y: Float, val z: Float) : SensorEvent
data class LocEvent(
    override val tMs: Double, val lat: Double, val lon: Double, val accM: Float,
    val speedMps: Float?, val speedAccMps: Float?, val bearingDeg: Float?, val bearingAccDeg: Float?,
) : SensorEvent
data class GnssStatusEvent(override val tMs: Double, val sats: Int, val used: Int, val cn0Mean: Float?) : SensorEvent

object SensorLogFormat {
    fun header(startedMs: Long): String = "{\"v\":1,\"type\":\"sensors\",\"started_ms\":$startedMs}"

    fun isWritable(e: SensorEvent): Boolean {
        if (!e.tMs.isFinite()) return false
        return when (e) {
            is GyroEvent -> e.x.isFinite() && e.y.isFinite() && e.z.isFinite()
            is AccelEvent -> e.x.isFinite() && e.y.isFinite() && e.z.isFinite()
            is LocEvent -> e.lat.isFinite() && e.lon.isFinite() && e.accM.isFinite()
            is GnssStatusEvent -> true
        }
    }

    fun line(e: SensorEvent): String = when (e) {
        is GyroEvent -> "{\"t\":${e.tMs},\"k\":\"g\",\"x\":${e.x},\"y\":${e.y},\"z\":${e.z}}"
        is AccelEvent -> "{\"t\":${e.tMs},\"k\":\"a\",\"x\":${e.x},\"y\":${e.y},\"z\":${e.z}}"
        is LocEvent -> {
            val spd = if (e.speedMps?.isFinite() == true) e.speedMps else null
            val spdAcc = if (e.speedAccMps?.isFinite() == true) e.speedAccMps else null
            val brg = if (e.bearingDeg?.isFinite() == true) e.bearingDeg else null
            val brgAcc = if (e.bearingAccDeg?.isFinite() == true) e.bearingAccDeg else null
            "{\"t\":${e.tMs},\"k\":\"loc\",\"lat\":${e.lat},\"lon\":${e.lon},\"acc\":${e.accM}," +
                "\"spd\":$spd,\"spd_acc\":$spdAcc,\"brg\":$brg,\"brg_acc\":$brgAcc}"
        }
        is GnssStatusEvent -> {
            val cn0 = if (e.cn0Mean?.isFinite() == true) e.cn0Mean else null
            "{\"t\":${e.tMs},\"k\":\"gnss\",\"sats\":${e.sats},\"used\":${e.used},\"cn0\":$cn0}"
        }
    }

    fun parse(line: String): SensorEvent? {
        val o: JsonObject = Json.parseToJsonElement(line).jsonObject
        if (o["type"] != null) return null
        val t = o.getValue("t").jsonPrimitive.double
        fun f(key: String) = o.getValue(key).jsonPrimitive.float
        fun fOrNull(key: String) = (o[key] as? JsonPrimitive)?.floatOrNull
        return when (val k = o.getValue("k").jsonPrimitive.content) {
            "g" -> GyroEvent(t, f("x"), f("y"), f("z"))
            "a" -> AccelEvent(t, f("x"), f("y"), f("z"))
            "loc" -> LocEvent(t, o.getValue("lat").jsonPrimitive.double, o.getValue("lon").jsonPrimitive.double,
                f("acc"), fOrNull("spd"), fOrNull("spd_acc"), fOrNull("brg"), fOrNull("brg_acc"))
            "gnss" -> GnssStatusEvent(t, o.getValue("sats").jsonPrimitive.int, o.getValue("used").jsonPrimitive.int, fOrNull("cn0"))
            else -> throw IllegalArgumentException("unknown sensor event kind '$k'")
        }
    }
}

/**
 * Пишется из потоков датчиков и GPS одновременно, поэтому методы синхронизированы.
 *
 * После close() или после первой IOException при записи (`broken`) все дальнейшие вызовы
 * header()/event() — no-op: одна сбойная запись (например, диск отключён на середине сессии) не
 * должна ронять поток датчиков/GPS исключением.
 */
class SensorLogger internal constructor(private val writer: java.io.Writer) : Closeable {
    constructor(file: File) : this(file.bufferedWriter())

    private var count = 0
    private var _skipped = 0
    private var _failed = 0
    private var closed = false
    private var broken = false

    val skipped: Int
        @Synchronized get() = _skipped

    val failed: Int
        @Synchronized get() = _failed

    @Synchronized fun header(startedMs: Long) {
        if (closed || broken) return
        try {
            writer.write(SensorLogFormat.header(startedMs)); writer.write("\n"); writer.flush()
        } catch (e: java.io.IOException) {
            _failed++
            broken = true
        }
    }

    @Synchronized fun event(e: SensorEvent) {
        if (closed || broken) return
        if (!SensorLogFormat.isWritable(e)) {
            _skipped++
            return
        }
        try {
            writer.write(SensorLogFormat.line(e)); writer.write("\n")
            if (++count % 200 == 0) writer.flush()
        } catch (ex: java.io.IOException) {
            _failed++
            broken = true
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        writer.flush()
        writer.close()
    }
}

fun readSensorLog(file: File): List<SensorEvent> {
    val lines = file.readLines().filter { it.isNotBlank() }
    val out = ArrayList<SensorEvent>(lines.size)
    for ((i, line) in lines.withIndex()) {
        val e = try {
            SensorLogFormat.parse(line)
        } catch (ex: Exception) {
            // Intentionally skip truncated last line (incomplete write after crash)
            if (i == lines.lastIndex) null else throw IllegalArgumentException("${file.name}:${i + 1}: ${ex.message}", ex)
        }
        if (e != null) out.add(e)
    }
    return out.sortedBy { it.tMs }
}
