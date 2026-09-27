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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** События датчиков; t — настенное время телефона, мс. Формат — контракт .sensors.jsonl v1 (план M2a). */
sealed interface SensorEvent { val tMs: Double }
data class GyroEvent(override val tMs: Double, val x: Float, val y: Float, val z: Float) : SensorEvent
data class AccelEvent(override val tMs: Double, val x: Float, val y: Float, val z: Float) : SensorEvent
data class LocEvent(
    override val tMs: Double, val lat: Double, val lon: Double, val accM: Float,
    val speedMps: Float?, val speedAccMps: Float?, val bearingDeg: Float?, val bearingAccDeg: Float?,
) : SensorEvent
data class GnssStatusEvent(
    override val tMs: Double, val sats: Int, val used: Int, val cn0Mean: Float?,
    val cn0Std: Float? = null, val cn0Max: Float? = null,
    val usedGps: Int? = null, val usedGlo: Int? = null, val usedGal: Int? = null, val usedBds: Int? = null,
) : SensorEvent

/** Средний уровень автоматической регулировки усиления приёмника GNSS (дБ) по n измерениям. */
data class AgcEvent(override val tMs: Double, val agcDb: Float, val n: Int) : SensorEvent

/** Синхронизация часов: настенное время, elapsedRealtimeNanos и System.nanoTime() для того же
 * момента — monoNs позволяет позже перевести метки, снятые по System.nanoTime() (например, часть
 * камер через Camera2/CameraX), а не только по elapsedRealtimeNanos (см. FrameCaptureEvent). */
data class ClockEvent(override val tMs: Double, val wallMs: Long, val elapsedNs: Long, val monoNs: Long) : SensorEvent

/** Некалиброванный гироскоп (рад/с): values[0..2] — угловая скорость, values[3..5] — оценка смещения. */
data class GyroUncalEvent(
    override val tMs: Double, val x: Float, val y: Float, val z: Float,
    val bx: Float, val by: Float, val bz: Float,
) : SensorEvent

/**
 * Время захвата кадра камеры — чисто диагностическое, replay это событие игнорирует.
 *
 * `t`/`frameTMs` — настенное время кадра (то же значение, что и `t_ms` соответствующей записи в
 * `.jsonl`; хранится явно как `frame_t_ms`, чтобы можно было сопоставить событие с кадром без
 * повторной конвертации). НЕ переводится из `capNs`: конвертация через смещение SensorRecorder
 * предполагает, что метка кадра снята по elapsedRealtimeNanos, а камера (ImageProxy.imageInfo.
 * timestamp) на части устройств использует другие часы — ложная конвертация может увести `t` на
 * часы вперёд/назад. `capNs` — сырая метка камеры (нс, источник зависит от устройства/реализации
 * CameraX) сохраняется как есть; сопоставить её с часами телефона можно позже через `clk`
 * (elapsed_ns или mono_ns), когда источник метки камеры известен.
 */
data class FrameCaptureEvent(override val tMs: Double, val frameTMs: Long, val capNs: Long) : SensorEvent

object SensorLogFormat {
    fun header(startedMs: Long): String = "{\"v\":1,\"type\":\"sensors\",\"started_ms\":$startedMs}"

    fun isWritable(e: SensorEvent): Boolean {
        if (!e.tMs.isFinite()) return false
        return when (e) {
            is GyroEvent -> e.x.isFinite() && e.y.isFinite() && e.z.isFinite()
            is AccelEvent -> e.x.isFinite() && e.y.isFinite() && e.z.isFinite()
            is LocEvent -> e.lat.isFinite() && e.lon.isFinite() && e.accM.isFinite()
            is GnssStatusEvent -> true
            is AgcEvent -> e.agcDb.isFinite()
            is ClockEvent -> true
            is GyroUncalEvent -> e.x.isFinite() && e.y.isFinite() && e.z.isFinite() &&
                e.bx.isFinite() && e.by.isFinite() && e.bz.isFinite()
            is FrameCaptureEvent -> true
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
            fun f(v: Float?) = if (v?.isFinite() == true) v else null
            "{\"t\":${e.tMs},\"k\":\"gnss\",\"sats\":${e.sats},\"used\":${e.used},\"cn0\":${f(e.cn0Mean)}," +
                "\"cn0_std\":${f(e.cn0Std)},\"cn0_max\":${f(e.cn0Max)},\"used_gps\":${e.usedGps}," +
                "\"used_glo\":${e.usedGlo},\"used_gal\":${e.usedGal},\"used_bds\":${e.usedBds}}"
        }
        is AgcEvent -> "{\"t\":${e.tMs},\"k\":\"agc\",\"agc_db\":${e.agcDb},\"n\":${e.n}}"
        is ClockEvent -> "{\"t\":${e.tMs},\"k\":\"clk\",\"wall_ms\":${e.wallMs}," +
            "\"elapsed_ns\":${e.elapsedNs},\"mono_ns\":${e.monoNs}}"
        is GyroUncalEvent -> "{\"t\":${e.tMs},\"k\":\"gu\",\"x\":${e.x},\"y\":${e.y},\"z\":${e.z}," +
            "\"bx\":${e.bx},\"by\":${e.by},\"bz\":${e.bz}}"
        is FrameCaptureEvent -> "{\"t\":${e.tMs},\"k\":\"frame\",\"frame_t_ms\":${e.frameTMs},\"cap_ns\":${e.capNs}}"
    }

    fun parse(line: String): SensorEvent? {
        val o: JsonObject = Json.parseToJsonElement(line).jsonObject
        if (o["type"] != null) return null
        val t = o.getValue("t").jsonPrimitive.double
        fun f(key: String) = o.getValue(key).jsonPrimitive.float
        fun fOrNull(key: String) = (o[key] as? JsonPrimitive)?.floatOrNull
        fun iOrNull(key: String) = (o[key] as? JsonPrimitive)?.intOrNull
        fun l(key: String) = o.getValue(key).jsonPrimitive.long
        return when (o.getValue("k").jsonPrimitive.content) {
            "g" -> GyroEvent(t, f("x"), f("y"), f("z"))
            "a" -> AccelEvent(t, f("x"), f("y"), f("z"))
            "loc" -> LocEvent(t, o.getValue("lat").jsonPrimitive.double, o.getValue("lon").jsonPrimitive.double,
                f("acc"), fOrNull("spd"), fOrNull("spd_acc"), fOrNull("brg"), fOrNull("brg_acc"))
            "gnss" -> GnssStatusEvent(t, o.getValue("sats").jsonPrimitive.int, o.getValue("used").jsonPrimitive.int,
                fOrNull("cn0"), fOrNull("cn0_std"), fOrNull("cn0_max"),
                iOrNull("used_gps"), iOrNull("used_glo"), iOrNull("used_gal"), iOrNull("used_bds"))
            "agc" -> AgcEvent(t, f("agc_db"), o.getValue("n").jsonPrimitive.int)
            "clk" -> ClockEvent(t, l("wall_ms"), l("elapsed_ns"), l("mono_ns"))
            "gu" -> GyroUncalEvent(t, f("x"), f("y"), f("z"), f("bx"), f("by"), f("bz"))
            "frame" -> FrameCaptureEvent(t, l("frame_t_ms"), l("cap_ns"))
            // Контракт .sensors.jsonl v1: расширяется аддитивно, читатели игнорируют неизвестные виды событий.
            else -> null
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

    /** Вызывается ровно один раз — когда логгер впервые ломается (IOException при header()/event()). */
    @Volatile var onFirstFailure: ((java.io.IOException) -> Unit)? = null

    val skipped: Int
        @Synchronized get() = _skipped

    val failed: Int
        @Synchronized get() = _failed

    @Synchronized fun header(startedMs: Long) {
        if (closed || broken) return
        try {
            writer.write(SensorLogFormat.header(startedMs)); writer.write("\n"); writer.flush()
        } catch (e: java.io.IOException) {
            fail(e)
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
            fail(ex)
        }
    }

    private fun fail(e: java.io.IOException) {
        _failed++
        val firstFailure = !broken
        broken = true
        // Колбэк — чужой код; если он бросит исключение, это не должно превратить header()/event()
        // (задуманные как никогда не бросающие) в исключение наружу.
        if (firstFailure) runCatching { onFirstFailure?.invoke(e) }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try {
            writer.flush()
        } finally {
            writer.close()
        }
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
