package io.visnav.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Формат журнала — контракт с research/vpr_bench/src/vpr_bench/fieldlog.py. Порядок и имена полей не менять.
@Serializable data class GpsJson(
    val lat: Double, val lon: Double, @SerialName("acc_m") val accM: Float, @SerialName("t_ms") val tMs: Long,
)
@Serializable data class PriorJson(val lat: Double, val lon: Double, @SerialName("radius_m") val radiusM: Double)
@Serializable data class HitJson(val i: Int, val sim: Float, val lat: Double, val lon: Double)
@Serializable data class FixJson(val lat: Double, val lon: Double, val sim: Float)
@Serializable data class LatencyJson(val pre: Double, val inf: Double, val search: Double)

@Serializable data class FrameRecord(
    val v: Int = 1,
    val type: String = "frame",
    @SerialName("t_ms") val tMs: Long,
    val mode: String,
    val gps: GpsJson?,
    val prior: PriorJson?,
    val top: List<HitJson>,
    val fix: FixJson?,
    @SerialName("lat_ms") val latMs: LatencyJson,
)

@Serializable data class SessionHeader(
    val v: Int = 1,
    val type: String = "session",
    val model: String,
    @SerialName("refpack_created_at") val refpackCreatedAt: String,
    val device: String,
    @SerialName("started_ms") val startedMs: Long,
    val mode: String,
)

object LogJson {
    private val json = Json { encodeDefaults = true }
    fun line(r: FrameRecord): String = json.encodeToString(FrameRecord.serializer(), r)
    fun line(h: SessionHeader): String = json.encodeToString(SessionHeader.serializer(), h)
}
