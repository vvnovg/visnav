package io.visnav.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

enum class ManeuverType { DEPART, CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }

/**
 * Манёвр на маршруте: atM — расстояние от начала маршрута, (e, n) — точка, street — улица после манёвра,
 * angleDeg — поворот в градусах (положительный — вправо; у слитых манёвров — сумма).
 */
data class Maneuver(
    val type: ManeuverType, val atM: Double, val e: Double, val n: Double, val street: String?, val exit: Int = 0,
    val angleDeg: Double = 0.0,
)

private const val MERGE_M = 30.0
private const val BEARING_WINDOW_M = 25.0
private const val STRAIGHT_DEG = 20.0
private const val FORK_DEG = 45.0
private const val MERGED_UTURN_DEG = 150.0
private val TURNS = setOf(
    ManeuverType.SLIGHT_LEFT, ManeuverType.LEFT, ManeuverType.SHARP_LEFT, ManeuverType.SLIGHT_RIGHT,
    ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.UTURN,
)

private fun exitNode(index: RoadIndex, s: RouteStep): Int =
    if (s.forward) index.pack.to[s.edge] else index.pack.from[s.edge]

/** Точка маршрута на расстоянии s от начала (s ограничено длиной маршрута). */
private fun pointAt(route: Route, s: Double): DoubleArray {
    val d = s.coerceIn(0.0, route.lengthM)
    var k = 0
    while (k < route.cumM.size - 2 && route.cumM[k + 1] < d) k++
    val len = route.cumM[k + 1] - route.cumM[k]
    val t = if (len <= 0.0) 0.0 else ((d - route.cumM[k]) / len).coerceIn(0.0, 1.0)
    val a = route.points[k]; val b = route.points[k + 1]
    return doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
}

private fun bearingBetween(a: DoubleArray, b: DoubleArray): Double = atan2(b[0] - a[0], b[1] - a[1])

/** Начальный азимут ребра при выезде из узла v; null, если из v по нему ехать нельзя. */
private fun outBearing(index: RoadIndex, edge: Int, v: Int): Double? {
    val pack = index.pack
    return when {
        pack.from[edge] == v -> index.bearing[edge]
        pack.to[edge] == v && !pack.oneway(edge) -> wrapAngle(index.bearing[edge] + PI)
        else -> null
    }
}

/**
 * Манёвры маршрута: DEPART, повороты на перекрёстках (узлы степени ≥ 3), развилки, круговое движение, ARRIVE.
 * Азимуты входа и выхода берутся по маршруту на ~25 м от узла. Мини-кольца (теги узлов) не моделируются.
 */
fun buildManeuvers(route: Route, index: RoadIndex): List<Maneuver> {
    val pack = index.pack
    val steps = route.steps
    val out = ArrayList<Maneuver>()
    out.add(Maneuver(ManeuverType.DEPART, 0.0, route.points[0][0], route.points[0][1], pack.name(steps[0].edge)))
    var i = 1
    while (i < steps.size) {
        val prev = steps[i - 1]; val cur = steps[i]
        val v = exitNode(index, prev)
        val at = route.cumM[i]; val pe = route.points[i][0]; val pn = route.points[i][1]
        if (pack.roundabout(prev.edge)) { i++; continue }   // внутри кольца и на съезде манёвров нет
        if (pack.roundabout(cur.edge)) {
            var exits = 0
            var j = i
            while (j < steps.size && pack.roundabout(steps[j].edge)) {
                val node = exitNode(index, steps[j])
                if (index.incident[node].any { !pack.roundabout(it) && outBearing(index, it, node) != null }) exits++
                j++
            }
            if (j >= steps.size) break                      // маршрут кончается внутри кольца
            out.add(Maneuver(ManeuverType.ROUNDABOUT, at, pe, pn, pack.name(steps[j].edge), maxOf(1, exits)))
            i = j + 1
            continue
        }
        if (index.degree[v] >= 3) {
            val node = doubleArrayOf(pe, pn)
            val bIn = bearingBetween(pointAt(route, at - BEARING_WINDOW_M), node)
            val bOut = bearingBetween(node, pointAt(route, at + BEARING_WINDOW_M))
            val a = Math.toDegrees(wrapAngle(bOut - bIn))
            val mag = abs(a)
            val right = a > 0
            val street = pack.name(cur.edge)
            val type: ManeuverType? = if (mag < FORK_DEG) {
                // Почти прямо (< 20°): развилка, только если конкурент не менее важен, чем наше ребро.
                val rivals = index.incident[v].filter { it != prev.edge && it != cur.edge }.mapNotNull { e ->
                    val b = outBearing(index, e, v) ?: return@mapNotNull null
                    if (abs(Math.toDegrees(wrapAngle(b - bIn))) >= FORK_DEG) return@mapNotNull null
                    if (mag < STRAIGHT_DEG && (pack.cls[e].toInt() and 0xFF) > (pack.cls[cur.edge].toInt() and 0xFF)) return@mapNotNull null
                    b
                }
                val rival = rivals.minByOrNull { abs(wrapAngle(bOut - it)) }
                when {
                    rival != null -> if (wrapAngle(bOut - rival) > 0) ManeuverType.SLIGHT_RIGHT else ManeuverType.SLIGHT_LEFT
                    street != null && street != pack.name(prev.edge) -> ManeuverType.CONTINUE
                    else -> null
                }
            } else when {
                mag < 135 -> if (right) ManeuverType.RIGHT else ManeuverType.LEFT
                mag < 170 -> if (right) ManeuverType.SHARP_RIGHT else ManeuverType.SHARP_LEFT
                else -> ManeuverType.UTURN
            }
            if (type != null) {
                val last = out.last()
                val sameSide = sameSideTurn(last.type, type)
                val mergeable = last.type in TURNS && (type == ManeuverType.CONTINUE || sameSide)
                if (mergeable && at - last.atM < MERGE_M) {
                    val sum = last.angleDeg + a
                    val merged = if (sameSide && abs(sum) >= MERGED_UTURN_DEG) ManeuverType.UTURN else last.type
                    out[out.size - 1] = last.copy(type = merged, street = street, angleDeg = sum)
                } else {
                    out.add(Maneuver(type, at, pe, pn, street, angleDeg = a))
                }
            }
        }
        i++
    }
    val end = route.points.last()
    out.add(Maneuver(ManeuverType.ARRIVE, route.lengthM, end[0], end[1], null))
    return out
}

private fun sameSideTurn(a: ManeuverType, b: ManeuverType): Boolean {
    val left = setOf(ManeuverType.LEFT, ManeuverType.SHARP_LEFT)
    val right = setOf(ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT)
    return (a in left && b in left) || (a in right && b in right)
}
