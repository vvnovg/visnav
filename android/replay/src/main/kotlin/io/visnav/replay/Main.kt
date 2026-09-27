package io.visnav.replay

import io.visnav.core.RefPack
import io.visnav.core.RefPackMeta
import java.io.File
import java.nio.ByteBuffer
import kotlin.system.exitProcess

private const val USAGE =
    "usage: replay --session <prefix> --refpack <dir> --out <file> [--outage START_S:LEN_S]... [--no-visual]"

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

    val data = SessionData.load(File(session))
    val meta = RefPackMeta.parse(File(refpack, "refpack.json").readText())
    if (meta.createdAt != data.header.refpackCreatedAt) {
        fail("refpack created_at ${meta.createdAt} != session ${data.header.refpackCreatedAt}")
    }
    val pack = RefPack.parse(ByteBuffer.wrap(File(refpack, "refpack.bin").readBytes()))
    val t0 = data.header.startedMs
    val outages = outageSpecs.map { spec ->
        val (start, len) = spec.split(":").map { it.toDoubleOrNull() ?: fail("bad --outage $spec") }
        if (start < 0 || len <= 0) fail("bad --outage $spec")
        Outage(t0 + (start * 1000).toLong(), t0 + ((start + len) * 1000).toLong())
    }
    val config = ReplayConfig(visual = visual)
    val points = Replayer(pack, config).run(data, outages)
    TrajectoryWriter.write(File(out), data, config, outages, points)
    println("${points.size} points (${points.count { it.inOutage }} in outages) -> $out")
}

private fun fail(message: String): Nothing {
    System.err.println("error: $message\n$USAGE")
    exitProcess(2)
}
