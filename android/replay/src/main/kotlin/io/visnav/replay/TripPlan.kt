package io.visnav.replay

import io.visnav.core.Enu
import io.visnav.core.RoadIndex
import io.visnav.core.RoadPack
import io.visnav.core.Router
import io.visnav.core.RouterConfig
import io.visnav.core.Geo.haversineM
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant

private const val TRIP_PLAN_USAGE =
    "usage: replay trip-plan --roads DIR --from LAT,LON --to LAT,LON [--via LAT,LON]... --out FILE.gpx"

class TripRoute(val latLon: List<DoubleArray>, val lengthM: Double, val durationS: Double)

/** Маршрут через промежуточные точки: ноги по одной, каждая — от проекции конца предыдущей. Курс неизвестен. */
fun planTrip(pack: RoadPack, waypoints: List<DoubleArray>, config: RouterConfig = RouterConfig()): TripRoute? {
    require(waypoints.size >= 2) { "need at least from and to" }
    val enu = Enu(waypoints[0][0], waypoints[0][1])
    val router = Router(RoadIndex(pack, enu), config)
    val pts = ArrayList<DoubleArray>()
    var length = 0.0; var duration = 0.0
    var cur = enu.toEn(waypoints[0][0], waypoints[0][1])
    for (w in waypoints.drop(1)) {
        val target = enu.toEn(w[0], w[1])
        val r = router.route(cur[0], cur[1], null, target[0], target[1]) ?: return null
        val legPts = r.points.map { enu.toLatLon(it[0], it[1]) }
        pts.addAll(if (pts.isEmpty()) legPts else legPts.drop(1))
        length += r.lengthM; duration += r.durationS
        cur = r.points.last()
    }
    return TripRoute(pts, length, duration)
}

fun densify(latLon: List<DoubleArray>, maxStepM: Double = 25.0): List<DoubleArray> {
    val out = ArrayList<DoubleArray>()
    for (p in latLon) {
        val last = out.lastOrNull()
        if (last == null) { out += p; continue }
        val d = haversineM(last[0], last[1], p[0], p[1])
        if (d < 1e-6) continue
        val k = kotlin.math.ceil(d / maxStepM).toInt()
        for (j in 1..k) {
            val t = j.toDouble() / k
            out += doubleArrayOf(last[0] + (p[0] - last[0]) * t, last[1] + (p[1] - last[1]) * t)
        }
    }
    return out
}

fun writeGpx(file: File, latLon: List<DoubleArray>, speedMps: Double = 10.0) {
    val t0 = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()
    var distM = 0.0
    val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"visnav trip-plan\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><trkseg>\n")
    latLon.forEachIndexed { i, p ->
        if (i > 0) distM += haversineM(latLon[i - 1][0], latLon[i - 1][1], p[0], p[1])
        val t = Instant.ofEpochMilli(t0 + (distM / speedMps * 1000).toLong())
        sb.append("<trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time></trkpt>\n".format(java.util.Locale.ROOT, p[0], p[1], t))
    }
    sb.append("</trkseg></trk>\n</gpx>\n")
    file.parentFile?.mkdirs()
    file.writeText(sb.toString())
}

private fun tripPlanError(message: String, usage: Boolean = true): Int {
    System.err.println(if (usage) "error: $message\n$TRIP_PLAN_USAGE" else "error: $message")
    return 2
}

/** CLI `replay trip-plan`: маршрут по roadpack каталога через точки, плотный GPX со временем. 0 — успех, 2 — ошибка. */
fun tripPlanMain(args: List<String>): Int {
    var roadsDir: String? = null; var fromSpec: String? = null; var toSpec: String? = null; var out: String? = null
    val viaSpecs = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--roads" -> roadsDir = args.getOrNull(++i) ?: return tripPlanError("--roads needs DIR")
            "--from" -> fromSpec = args.getOrNull(++i) ?: return tripPlanError("--from needs LAT,LON")
            "--to" -> toSpec = args.getOrNull(++i) ?: return tripPlanError("--to needs LAT,LON")
            "--via" -> viaSpecs += args.getOrNull(++i) ?: return tripPlanError("--via needs LAT,LON")
            "--out" -> out = args.getOrNull(++i) ?: return tripPlanError("--out needs FILE.gpx")
            else -> return tripPlanError("unknown argument ${args[i]}")
        }
        i++
    }
    if (roadsDir == null || fromSpec == null || toSpec == null || out == null) return tripPlanError("missing required argument")
    val waypoints = ArrayList<DoubleArray>()
    for ((flag, s) in listOf("--from" to fromSpec) + viaSpecs.map { "--via" to it } + listOf("--to" to toSpec)) {
        val p = parseDest(s) ?: return tripPlanError("bad $flag $s (expected LAT,LON within [-90,90],[-180,180])")
        waypoints += doubleArrayOf(p.first, p.second)
    }
    val bin = File(roadsDir, "roadpack.bin")
    if (!bin.isFile) return tripPlanError("roadpack data not found: $bin")
    val pack = try {
        RoadPack.parse(ByteBuffer.wrap(bin.readBytes()))
    } catch (e: IllegalArgumentException) {
        return tripPlanError(e.message ?: "bad roadpack in $roadsDir")
    }
    val route = planTrip(pack, waypoints)
        ?: return tripPlanError("no route (a waypoint is off the road graph or unreachable)", usage = false)
    val pts = densify(route.latLon)
    writeGpx(File(out), pts)
    println("route %.1f km, ~%d min, %d points -> %s".format(
        java.util.Locale.ROOT, route.lengthM / 1000, Math.round(route.durationS / 60), pts.size, out))
    return 0
}
