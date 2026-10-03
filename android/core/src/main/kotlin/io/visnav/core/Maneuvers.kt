package io.visnav.core

import kotlin.math.PI
import kotlin.math.abs

enum class ManeuverType { DEPART, CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }

/** Манёвр на маршруте: atM — расстояние от начала маршрута, (e, n) — точка, street — улица после манёвра. */
data class Maneuver(
    val type: ManeuverType, val atM: Double, val e: Double, val n: Double, val street: String?, val exit: Int = 0,
)

private const val MERGE_M = 30.0
private val TURNS = setOf(
    ManeuverType.SLIGHT_LEFT, ManeuverType.LEFT, ManeuverType.SHARP_LEFT, ManeuverType.SLIGHT_RIGHT,
    ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.UTURN,
)

private fun bearingOf(index: RoadIndex, s: RouteStep): Double =
    if (s.forward) index.bearing[s.edge] else wrapAngle(index.bearing[s.edge] + PI)

private fun exitNode(index: RoadIndex, s: RouteStep): Int =
    if (s.forward) index.pack.to[s.edge] else index.pack.from[s.edge]

/** Манёвры маршрута: DEPART, повороты на перекрёстках (узлы степени ≥ 3), круговое движение, ARRIVE. */
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
        if (pack.roundabout(cur.edge) && !pack.roundabout(prev.edge)) {
            var exits = 0
            var j = i
            while (j < steps.size && pack.roundabout(steps[j].edge)) {
                val node = exitNode(index, steps[j])
                if (index.incident[node].any { !pack.roundabout(it) }) exits++
                j++
            }
            val street = if (j < steps.size) pack.name(steps[j].edge) else null
            out.add(Maneuver(ManeuverType.ROUNDABOUT, at, pe, pn, street, maxOf(1, exits)))
            i = j + 1
            continue
        }
        if (index.degree[v] >= 3) {
            val a = Math.toDegrees(wrapAngle(bearingOf(index, cur) - bearingOf(index, prev)))
            val mag = abs(a)
            val right = a > 0
            val type = when {
                mag < 20 -> if (pack.name(cur.edge) != pack.name(prev.edge)) ManeuverType.CONTINUE else null
                mag < 45 -> if (right) ManeuverType.SLIGHT_RIGHT else ManeuverType.SLIGHT_LEFT
                mag < 135 -> if (right) ManeuverType.RIGHT else ManeuverType.LEFT
                mag < 170 -> if (right) ManeuverType.SHARP_RIGHT else ManeuverType.SHARP_LEFT
                else -> ManeuverType.UTURN
            }
            if (type != null) {
                val m = Maneuver(type, at, pe, pn, pack.name(cur.edge))
                val last = out.last()
                val mergeable = last.type in TURNS && (type == ManeuverType.CONTINUE || sameSideTurn(last.type, type))
                if (mergeable && at - last.atM < MERGE_M) {
                    out[out.size - 1] = last.copy(street = m.street)
                } else {
                    out.add(m)
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
