package io.visnav.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

enum class ManeuverType { DEPART, CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }

/**
 * Манёвр на маршруте: atM — расстояние от начала маршрута, (e, n) — точка, street — улица после манёвра,
 * angleDeg — поворот в градусах (положительный — вправо; у слитых манёвров — от входа первого до выхода последнего).
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
    var lo = 0; var hi = route.cumM.size - 2          // первый k, для которого cumM[k + 1] >= d
    while (lo < hi) { val mid = (lo + hi) ushr 1; if (route.cumM[mid + 1] < d) lo = mid + 1 else hi = mid }
    val k = lo
    val len = route.cumM[k + 1] - route.cumM[k]
    val t = if (len <= 0.0) 0.0 else ((d - route.cumM[k]) / len).coerceIn(0.0, 1.0)
    val a = route.points[k]; val b = route.points[k + 1]
    return doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
}

private const val MIN_WINDOW_M = 0.5

private fun bearingBetween(a: DoubleArray, b: DoubleArray): Double = atan2(b[0] - a[0], b[1] - a[1])

private fun dist(a: DoubleArray, b: DoubleArray): Double = hypot(b[0] - a[0], b[1] - a[1])

/**
 * Азимут соперника: от узла v по его геометрии на ~25 м (через узлы степени 2), чтобы изгиб дороги сразу за
 * перекрёстком не искажал сравнение (останавливается на ближайшем перекрёстке, в отличие от окна маршрута); null, если из v по ребру ехать нельзя.
 */
private fun rivalBearing(index: RoadIndex, edge: Int, v: Int): Double? {
    val first = outBearing(index, edge, v) ?: return null
    val pack = index.pack
    val origin = doubleArrayOf(index.nodeE[v], index.nodeN[v])
    var cur = edge; var node = v; var acc = 0.0
    var target: DoubleArray = origin
    for (step in 0 until 32) {
        val w = if (pack.from[cur] == node) pack.to[cur] else pack.from[cur]
        val a = doubleArrayOf(index.nodeE[node], index.nodeN[node]); val b = doubleArrayOf(index.nodeE[w], index.nodeN[w])
        val len = dist(a, b)
        if (acc + len >= BEARING_WINDOW_M) {
            val t = if (len == 0.0) 0.0 else (BEARING_WINDOW_M - acc) / len
            target = doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
            break
        }
        acc += len; target = b
        val next = if (index.degree[w] == 2 && w != v) index.incident[w].firstOrNull { it != cur } else null
        if (next == null) break
        cur = next; node = w
    }
    return if (dist(origin, target) < MIN_WINDOW_M) first else bearingBetween(origin, target)
}

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
 * Манёвры маршрута: DEPART, повороты на перекрёстках (узлы степени ≥ 3), развилки, круговое движение, разворот в
 * тупике, ARRIVE.
 * Азимуты входа и выхода берутся по маршруту на ~25 м от узла. Мини-кольца (теги узлов) не моделируются.
 */
fun buildManeuvers(route: Route, index: RoadIndex): List<Maneuver> {
    val pack = index.pack
    val steps = route.steps
    val out = ArrayList<Maneuver>()
    out.add(Maneuver(ManeuverType.DEPART, 0.0, route.points[0][0], route.points[0][1], pack.name(steps[0].edge)))
    var lastBIn = 0.0   // входящий азимут последнего добавленного поворота (для слияния)
    if (route.startsAgainstHeading) {
        // Маршрут начинается против курса: сначала развернуться (на месте старта, улица первого шага).
        out.add(Maneuver(ManeuverType.UTURN, 0.0, route.points[0][0], route.points[0][1], pack.name(steps[0].edge),
            angleDeg = 180.0))
        val b = index.bearing[steps[0].edge]
        lastBIn = if (steps[0].forward) wrapAngle(b + PI) else b
    }
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
        if (cur.edge == prev.edge && cur.forward != prev.forward) {
            // Разворот на том же ребре — только в тупике (узел степени 1).
            val bIn = if (prev.forward) index.bearing[prev.edge] else wrapAngle(index.bearing[prev.edge] + PI)
            out.add(Maneuver(ManeuverType.UTURN, at, pe, pn, pack.name(cur.edge), angleDeg = 180.0))
            lastBIn = bIn
            i++
            continue
        }
        if (index.degree[v] >= 3) {
            val node = doubleArrayOf(pe, pn)
            val before = pointAt(route, at - BEARING_WINDOW_M); val after = pointAt(route, at + BEARING_WINDOW_M)
            // Окно нулевой длины (старт или финиш в самом узле): берём азимут ребра.
            val bIn = if (dist(before, node) < MIN_WINDOW_M) {
                if (prev.forward) index.bearing[prev.edge] else wrapAngle(index.bearing[prev.edge] + PI)
            } else bearingBetween(before, node)
            val bOut = if (dist(node, after) < MIN_WINDOW_M) outBearing(index, cur.edge, v) ?: bIn
            else bearingBetween(node, after)
            val a = Math.toDegrees(wrapAngle(bOut - bIn))
            val mag = abs(a)
            val right = a > 0
            val street = pack.name(cur.edge)
            val type: ManeuverType? = if (mag < FORK_DEG) {
                // Почти прямо (< 20°): развилка, только если конкурент не менее важен, чем наше ребро.
                val rivals = index.incident[v].filter { it != prev.edge && it != cur.edge }.mapNotNull { e ->
                    val b = rivalBearing(index, e, v) ?: return@mapNotNull null
                    if (abs(Math.toDegrees(wrapAngle(b - bIn))) >= FORK_DEG) return@mapNotNull null
                    // Почти прямо по основной дороге: съезды (_link) не считаются развилкой.
                    if (mag < STRAIGHT_DEG && pack.link(e) && !pack.link(cur.edge)) return@mapNotNull null
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
                    // Суммарный поворот — от входящего азимута первого манёвра до исходящего текущего.
                    val total = Math.toDegrees(wrapAngle(bOut - lastBIn))
                    val merged = if (sameSide && abs(total) >= MERGED_UTURN_DEG) ManeuverType.UTURN else last.type
                    out[out.size - 1] = last.copy(type = merged, street = street, angleDeg = total)
                } else {
                    out.add(Maneuver(type, at, pe, pn, street, angleDeg = a))
                    lastBIn = bIn
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
