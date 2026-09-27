package io.visnav.replay

import io.visnav.core.AccelEvent
import io.visnav.core.ClockEvent
import io.visnav.core.GyroEvent
import io.visnav.core.RefPack
import io.visnav.core.RefPackMeta
import io.visnav.core.SensorEvent
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.system.exitProcess

private const val USAGE =
    "usage: replay --session <prefix> --refpack <dir> --out <file> [--outage START_S:LEN_S]... [--no-visual]"
private const val FIRST_SENSOR_TIME_TOLERANCE_MS = 5_000.0
private const val CLOCK_DRIFT_WARN_MS = 1_000L

fun main(args: Array<String>) {
    var session: String? = null; var refpack: String? = null; var out: String? = null
    var visual = true
    val outageSpecs = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--session" -> session = args.getOrNull(++i)
            "--refpack" -> refpack = args.getOrNull(++i)
            "--out" -> out = args.getOrNull(++i)
            "--outage" -> outageSpecs += args.getOrNull(++i) ?: fail("--outage needs START_S:LEN_S")
            "--no-visual" -> visual = false
            else -> fail("unknown argument ${args[i]}")
        }
        i++
    }
    if (session == null || refpack == null || out == null) fail("missing required argument")
    // A --session copy-pasted with the frame log's own extension is a common slip; accept it.
    session = session.removeSuffix(".jsonl")

    val framesFile = File("$session.jsonl")
    if (!framesFile.isFile) fail("session file not found: $framesFile")
    val sensorsFile = File("$session.sensors.jsonl")
    if (!sensorsFile.isFile) fail("sensor log not found: $sensorsFile")
    val descFile = File("$session.desc")
    if (!descFile.isFile) fail("descriptor log not found: $descFile")
    val refpackJson = File(refpack, "refpack.json")
    val refpackBin = File(refpack, "refpack.bin")
    if (!refpackJson.isFile) fail("refpack meta not found: $refpackJson")
    if (!refpackBin.isFile) fail("refpack data not found: $refpackBin")

    val data = SessionData.load(File(session))
    val meta = RefPackMeta.parse(refpackJson.readText())
    if (meta.createdAt != data.header.refpackCreatedAt) {
        fail("refpack created_at ${meta.createdAt} != session ${data.header.refpackCreatedAt}")
    }
    val pack = RefPack.parse(ByteBuffer.wrap(refpackBin.readBytes()))
    if (data.descriptors.dim != pack.dim) {
        fail("descriptor dim ${data.descriptors.dim} != refpack dim ${pack.dim}")
    }
    if (!firstSensorTimeOk(data.sensors, data.header.startedMs)) {
        val first = firstImuEvent(data.sensors)
        fail(
            "first IMU sensor event t=${first?.tMs} is more than ${FIRST_SENSOR_TIME_TOLERANCE_MS.toLong()} ms " +
                "away from session started_ms=${data.header.startedMs} — sensors and session clock disagree"
        )
    }
    clockDriftMaxMs(data.sensors.filterIsInstance<ClockEvent>())?.let { drift ->
        println("clock drift max $drift ms")
        if (drift > CLOCK_DRIFT_WARN_MS) {
            System.err.println("warning: clock drift max $drift ms exceeds ${CLOCK_DRIFT_WARN_MS} ms")
        }
    }

    val t0 = data.header.startedMs
    val outages = outageSpecs.map { spec -> parseOutage(spec, t0) }
    val config = ReplayConfig(visual = visual)
    val points = Replayer(pack, config).run(data, outages)
    if (visual && allFramesLackDescriptor(points)) {
        fail("visual mode: no frame had a descriptor (all vis_state=no_desc) — session/refpack mismatch?")
    }
    TrajectoryWriter.write(File(out), data, config, outages, points)
    println("${points.size} points (${points.count { it.inOutage }} in outages) -> $out")
}

/** The earliest IMU (gyro/accel) event, or null if there are none. Restricted to IMU on purpose:
 * other SensorEvent kinds can carry a `t` derived from a different clock domain — notably
 * FrameCaptureEvent, whose `t` is the frame's own wall time and NOT converted through
 * SensorRecorder's elapsedRealtimeNanos-calibrated offset (see FrameCaptureEvent's kdoc) — so mixing
 * them into a single "earliest sensor event" would compare apples to oranges. */
internal fun firstImuEvent(sensors: List<SensorEvent>): SensorEvent? =
    sensors.asSequence().filter { it is GyroEvent || it is AccelEvent }.minByOrNull { it.tMs }

/** true unless the recorded IMU events (gyro/accel — see firstImuEvent) start more than 5 s away
 * from the session's own started_ms (a sign sensor timestamps and the session clock disagree —
 * see SensorRecorder's own first-event check). */
internal fun firstSensorTimeOk(
    sensors: List<SensorEvent>,
    startedMs: Long,
    toleranceMs: Double = FIRST_SENSOR_TIME_TOLERANCE_MS,
): Boolean {
    val first = firstImuEvent(sensors) ?: return true
    return abs(first.tMs - startedMs) <= toleranceMs
}

/** Max drift (ms) between wall_ms and elapsed_ns-derived wall time across "clk" events, using the
 * first clk line's offset as the reference; null if there are no clk lines to check. */
internal fun clockDriftMaxMs(clocks: List<ClockEvent>): Long? {
    if (clocks.isEmpty()) return null
    val offset0 = clocks[0].wallMs - clocks[0].elapsedNs / 1e6
    return clocks.maxOf { c -> abs(c.wallMs - (c.elapsedNs / 1e6 + offset0)) }.roundToLong()
}

/** true if there is at least one point and none of them ever got a descriptor in visual mode. */
internal fun allFramesLackDescriptor(points: List<TrajPoint>): Boolean =
    points.isNotEmpty() && points.all { it.visState == "no_desc" }

private fun parseOutage(spec: String, t0: Long): Outage {
    val parts = spec.split(":")
    if (parts.size != 2) fail("bad --outage $spec (expected START_S:LEN_S)")
    val start = parts[0].toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("bad --outage $spec")
    val len = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("bad --outage $spec")
    if (start < 0 || len <= 0) fail("bad --outage $spec (need start >= 0, len > 0)")
    return Outage(t0 + (start * 1000).toLong(), t0 + ((start + len) * 1000).toLong())
}

private fun fail(message: String): Nothing {
    System.err.println("error: $message\n$USAGE")
    exitProcess(2)
}
