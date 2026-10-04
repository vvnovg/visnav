package io.visnav.replay

import io.visnav.core.Enu
import io.visnav.core.RoadIndex
import io.visnav.core.RoadPack
import io.visnav.core.Route
import io.visnav.core.RouteStep
import io.visnav.core.Router
import io.visnav.core.RouterConfig
import io.visnav.core.Geo.haversineM
import io.visnav.core.wrapAngle
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.math.PI
import kotlin.math.hypot

private const val TRIP_PLAN_USAGE =
    "usage: replay trip-plan --roads DIR --from LAT,LON --to LAT,LON [--via LAT,LON]... --out FILE.gpx"

class TripRoute(val latLon: List<DoubleArray>, val lengthM: Double, val durationS: Double)

/**
 * Маршрут через промежуточные точки: ноги по одной. Первая нога — от точки from без курса. Каждая следующая —
 * с курсом прибытия предыдущей ноги (по её последнему ребру), чтобы в промежуточной точке не было бесплатного
 * разворота на месте.
 *
 * Обход ограничения Router: старт ровно в узле даёт кандидатами все рёбра узла на расстоянии 0, и ребро другой
 * улицы, «въезжающей» в узел под углом ≤ 90° к курсу, позволило бы уехать обратно. Поэтому следующая нога стартует
 * на d = min(1 м, половина пройденной части ребра прибытия) назад по ребру прибытия, со своим
 * RouterConfig(startSlackM = 0) — в кандидатах остаётся только ребро прибытия. Первая точка такой ноги (проекция
 * старта на d позади) отбрасывается; из длины вычитается d, из durationS — d / скорость ребра прибытия.
 *
 * Ограничения: если следующая точка проецируется на то же ребро позади via, возможен короткий шаг назад (≤ 1 м);
 * на стыке ног бывает повтор точки — его убирает [densify].
 */
fun planTrip(pack: RoadPack, waypoints: List<DoubleArray>, config: RouterConfig = RouterConfig()): TripRoute? {
    require(waypoints.size >= 2) { "need at least from and to" }
    val enu = Enu(waypoints[0][0], waypoints[0][1])
    val index = RoadIndex(pack, enu)
    val firstRouter = Router(index, config)
    val nextRouter = Router(index, config.copy(startSlackM = 0.0))
    val pts = ArrayList<DoubleArray>()
    var length = 0.0; var duration = 0.0
    var cur = enu.toEn(waypoints[0][0], waypoints[0][1])
    var arrival: RouteStep? = null
    for (w in waypoints.drop(1)) {
        val target = enu.toEn(w[0], w[1])
        val st = arrival
        val r: Route
        var backM = 0.0
        if (st == null) {
            r = firstRouter.route(cur[0], cur[1], null, target[0], target[1]) ?: return null
        } else {
            val e = st.edge
            // Узел, с которого въехали на ребро прибытия; отступаем от конца ноги к нему.
            val entry = if (st.forward) pack.from[e] else pack.to[e]
            val de = index.nodeE[entry] - cur[0]; val dn = index.nodeN[entry] - cur[1]
            val along = hypot(de, dn)
            backM = minOf(1.0, along / 2)
            val sE = if (along > 0) cur[0] + de / along * backM else cur[0]
            val sN = if (along > 0) cur[1] + dn / along * backM else cur[1]
            val heading = if (st.forward) index.bearing[e] else wrapAngle(index.bearing[e] + PI)
            r = nextRouter.route(sE, sN, heading, target[0], target[1]) ?: return null
            length -= backM; duration -= backM / pack.speedMps(e)
        }
        val legPts = r.points.map { enu.toLatLon(it[0], it[1]) }
        // Первая точка следующей ноги — её старт (≈ конец предыдущей или d позади него): отбрасывается.
        pts.addAll(if (pts.isEmpty()) legPts else legPts.drop(1))
        length += r.lengthM; duration += r.durationS
        cur = r.points.last()
        // Нога может закончиться в начале своего последнего шага (pos 0): Router выбирает одного кандидата на way, и
        // им бывает продолжение той же улицы за узлом, а у ребра нулевой длины направления нет. Тогда ребро
        // прибытия — предыдущий шаг (у одношаговой ноги — ребро прибытия предыдущей ноги).
        val last = r.steps.last()
        val lastEntry = if (last.forward) pack.from[last.edge] else pack.to[last.edge]
        val atEntry = hypot(index.nodeE[lastEntry] - cur[0], index.nodeN[lastEntry] - cur[1]) < 1e-6
        arrival = when {
            atEntry && r.steps.size >= 2 -> r.steps[r.steps.size - 2]
            atEntry && st != null -> st
            else -> last
        }
    }
    return TripRoute(pts, length, duration)
}

fun densify(latLon: List<DoubleArray>, maxStepM: Double = 25.0): List<DoubleArray> {
    val out = ArrayList<DoubleArray>()
    for (p in latLon) {
        val last = out.lastOrNull()
        if (last == null) { out += p; continue }
        val d = haversineM(last[0], last[1], p[0], p[1])
        if (d < 0.05) continue
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
    var prevMs = Long.MIN_VALUE
    val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"visnav trip-plan\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><trkseg>\n")
    latLon.forEachIndexed { i, p ->
        if (i > 0) distM += haversineM(latLon[i - 1][0], latLon[i - 1][1], p[0], p[1])
        // Время строго растёт: даже очень близкие точки получают разные метки (≥ 1 мс).
        val ms = if (i == 0) t0 else maxOf(t0 + (distM / speedMps * 1000).toLong(), prevMs + 1)
        prevMs = ms
        val t = Instant.ofEpochMilli(ms)
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
    // 24.9 м, а не 25: после округления координат до 7 знаков шаг в файле остаётся ≤ 25 м.
    val pts = densify(route.latLon, maxStepM = 24.9)
    try {
        writeGpx(File(out), pts)
    } catch (e: IOException) {
        return tripPlanError("cannot write $out: ${e.message}", usage = false)
    }
    println("route %.1f km, ~%d min est. (incl. turn penalties), %d points -> %s".format(
        java.util.Locale.ROOT, route.lengthM / 1000, Math.round(route.durationS / 60), pts.size, out))
    return 0
}
