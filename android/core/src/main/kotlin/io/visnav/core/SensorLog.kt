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

    fun line(e: SensorEvent): String = when (e) {
        is GyroEvent -> "{\"t\":${e.tMs},\"k\":\"g\",\"x\":${e.x},\"y\":${e.y},\"z\":${e.z}}"
        is AccelEvent -> "{\"t\":${e.tMs},\"k\":\"a\",\"x\":${e.x},\"y\":${e.y},\"z\":${e.z}}"
        is LocEvent -> "{\"t\":${e.tMs},\"k\":\"loc\",\"lat\":${e.lat},\"lon\":${e.lon},\"acc\":${e.accM}," +
            "\"spd\":${e.speedMps},\"spd_acc\":${e.speedAccMps},\"brg\":${e.bearingDeg},\"brg_acc\":${e.bearingAccDeg}}"
        is GnssStatusEvent -> "{\"t\":${e.tMs},\"k\":\"gnss\",\"sats\":${e.sats},\"used\":${e.used},\"cn0\":${e.cn0Mean}}"
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

/** Пишется из потоков датчиков и GPS одновременно, поэтому методы синхронизированы. */
class SensorLogger(file: File) : Closeable {
    private val writer = file.bufferedWriter()
    private var count = 0

    @Synchronized fun header(startedMs: Long) {
        writer.write(SensorLogFormat.header(startedMs)); writer.newLine(); writer.flush()
    }

    @Synchronized fun event(e: SensorEvent) {
        writer.write(SensorLogFormat.line(e)); writer.newLine()
        if (++count % 200 == 0) writer.flush()
    }

    @Synchronized override fun close() { writer.flush(); writer.close() }
}

fun readSensorLog(file: File): List<SensorEvent> {
    val lines = file.readLines().filter { it.isNotBlank() }
    val out = ArrayList<SensorEvent>(lines.size)
    for ((i, line) in lines.withIndex()) {
        val e = try {
            SensorLogFormat.parse(line)
        } catch (ex: Exception) {
            if (i == lines.lastIndex) null // обрезанная последняя строка после аварийной остановки
            else throw IllegalArgumentException("${file.name}:${i + 1}: ${ex.message}", ex)
        }
        if (e != null) out.add(e)
    }
    return out.sortedBy { it.tMs }
}
