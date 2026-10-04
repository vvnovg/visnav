package io.visnav.core

import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/** Шаг маршрута: ребро и направление проезда (forward — from → to). */
data class RouteStep(val edge: Int, val forward: Boolean)

/**
 * Маршрут: points[0] — проекция старта, points[i] (1 ≤ i < steps.size) — узел между шагами i−1 и i, последняя —
 * проекция финиша; cumM — накопленная длина до каждой точки.
 *
 * durationS — стоимость маршрута для поиска, а не чистое время в пути: кроме времени езды по рёбрам включает
 * штрафы за повороты (turnPenaltyS), за разворот в тупике (deadEndUturnPenaltyS) и за старт против курса
 * (wrongHeadingPenaltyS). Это оценка.
 */
class Route(val steps: List<RouteStep>, val points: List<DoubleArray>, val cumM: DoubleArray, val durationS: Double) {
    val lengthM: Double get() = cumM.last()
}

data class RouterConfig(
    val snapRadiusM: Double = 60.0,
    val destSnapRadiusM: Double = 150.0,
    /** Финиш — только на дорогах не дальше ближайшей к цели + goalSlackM. */
    val goalSlackM: Double = 25.0,
    /** Старт — только на дорогах не дальше ближайшей к точке старта + startSlackM (параллельные проезжие части). */
    val startSlackM: Double = 8.0,
    val maxCandidateWays: Int = 4,
    val turnPenaltyS: Double = 5.0,
    /**
     * Разворот на том же ребре в тупике: дороже обычного поворота, чтобы объезд квартала выигрывал у разворота
     * в коротком отростке. У края коридора (roadpack v3) разворот запрещён.
     */
    val deadEndUturnPenaltyS: Double = 120.0,
    val wrongHeadingPenaltyS: Double = 60.0,
    val maxSpeedMps: Double = 130 / 3.6,
)

/**
 * A* по направленным рёбрам графа коридора: односторонние улицы, запреты поворотов (no_* / only_*), стоимость —
 * время в пути. Разворот на том же ребре — только в тупике, со штрафом deadEndUturnPenaltyS; у края коридора
 * (roadpack v3) разворота нет. С курсом поиск идёт от стартов по курсу; если от них маршрута нет, поиск повторяется
 * один раз по всем стартам в пределах startSlackM, и старт против курса стоит wrongHeadingPenaltyS.
 */
class Router(private val index: RoadIndex, private val config: RouterConfig = RouterConfig()) {
    private val pack = index.pack
    /** Скорость для эвристики: не меньше самой быстрой дороги графа, чтобы h не переоценивала остаток пути. */
    private val vmax: Double =
        maxOf(config.maxSpeedMps, (0 until pack.edgeCount).maxOfOrNull { pack.speedMps(it) } ?: 0.0)
    private val byFromVia: Map<Long, List<TurnRestriction>> =
        pack.restrictions.groupBy { (it.fromEdge.toLong() shl 32) or it.via.toLong() }

    private class Cand(val proj: RoadProjection, val forward: Boolean, val pos: Double)

    private fun state(edge: Int, forward: Boolean) = edge * 2 + if (forward) 0 else 1
    private fun exitNode(edge: Int, forward: Boolean) = if (forward) pack.to[edge] else pack.from[edge]
    private fun travelBearing(edge: Int, forward: Boolean) =
        if (forward) index.bearing[edge] else wrapAngle(index.bearing[edge] + PI)

    private fun candidates(e: Double, n: Double, radius: Double): List<Cand> =
        index.near(e, n, radius).distinctBy { pack.way[it.edge] }.take(config.maxCandidateWays).flatMap { p ->
            val len = index.length[p.edge]
            listOfNotNull(
                Cand(p, true, p.t * len),
                if (pack.oneway(p.edge)) null else Cand(p, false, (1 - p.t) * len),
            )
        }

    private fun allowed(fromEdge: Int, via: Int, toEdge: Int): Boolean {
        val rs = byFromVia[(fromEdge.toLong() shl 32) or via.toLong()] ?: return true
        if (rs.any { !it.only && it.toEdge == toEdge }) return false
        val only = rs.filter { it.only }
        return only.isEmpty() || only.any { it.toEdge == toEdge }
    }

    /**
     * Маршрут от точки старта до цели. headingRad должен быть null, если машина не едет со скоростью ≥ 3 м/с с
     * достоверным курсом: на малой скорости курс — шум. Ответственность за это — на вызывающем.
     */
    fun route(fromE: Double, fromN: Double, headingRad: Double?, toE: Double, toN: Double): Route? {
        val startsAll = candidates(fromE, fromN, config.snapRadiusM)
        val goalsAll = candidates(toE, toN, config.destSnapRadiusM)
        if (startsAll.isEmpty() || goalsAll.isEmpty()) return null
        fun withinSlack(cs: List<Cand>): List<Cand> {
            val nearestStart = cs.minOf { it.proj.distM }
            return cs.filter { it.proj.distM <= nearestStart + config.startSlackM }
        }
        // С курсом: сначала кандидаты, в чью сторону едем (≤ 90°), затем запас startSlackM внутри них; если таких нет —
        // запас по всем кандидатам (штраф за встречное направление тогда остаётся).
        val headed = if (headingRad == null) null else
            startsAll.filter { abs(wrapAngle(headingRad - travelBearing(it.proj.edge, it.forward))) <= PI / 2 }
                .takeIf { it.isNotEmpty() }?.let { withinSlack(it) }
        val nearest = goalsAll.minOf { it.proj.distM }
        val goals = goalsAll.filter { it.proj.distM <= nearest + config.goalSlackM }
        if (headed == null) return search(withinSlack(startsAll), goals, headingRad)
        // По курсу маршрута нет (например, впереди край коридора, где разворот запрещён) — один повторный поиск по всем
        // кандидатам; старт против курса оплачивается wrongHeadingPenaltyS.
        return search(headed, goals, headingRad) ?: search(withinSlack(startsAll), goals, headingRad)
    }

    private fun search(starts: List<Cand>, goals: List<Cand>, headingRad: Double?): Route? {
        val goalOf = HashMap<Int, MutableList<Cand>>()
        for (g in goals) goalOf.getOrPut(state(g.proj.edge, g.forward)) { ArrayList() }.add(g)

        val nStates = pack.edgeCount * 2
        val gCost = DoubleArray(nStates) { Double.POSITIVE_INFINITY }
        val prev = IntArray(nStates) { -2 }      // -1 — стартовое состояние
        val startPos = DoubleArray(nStates)
        // Эвристика — до ближайшей проекции финиша, а не до самой цели: цель может лежать до destSnapRadiusM в
        // стороне от дороги, и расстояние до неё переоценило бы остаток пути (A* остановился бы на худшем маршруте).
        val goalPts = goals.map { doubleArrayOf(it.proj.e, it.proj.n) }
        fun h(s: Int): Double {
            val v = exitNode(s / 2, s % 2 == 0)
            return goalPts.minOf { hypot(index.nodeE[v] - it[0], index.nodeN[v] - it[1]) } / vmax
        }
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        var bestTotal = Double.POSITIVE_INFINITY
        var bestGoalState = -1
        var bestGoalPos = 0.0
        var bestGoalPrev = -1
        var directStart: Cand? = null

        for (c in starts) {
            val e = c.proj.edge
            val len = index.length[e]
            val speed = pack.speedMps(e)
            var cost = (len - c.pos) / speed
            if (headingRad != null && abs(wrapAngle(headingRad - travelBearing(e, c.forward))) > PI / 2) {
                cost += config.wrongHeadingPenaltyS
            }
            val s = state(e, c.forward)
            // Финиш на том же ребре впереди по ходу движения.
            goalOf[s]?.filter { it.pos >= c.pos }?.forEach { g ->
                val total = cost - (len - g.pos) / speed
                if (total < bestTotal) { bestTotal = total; bestGoalState = s; bestGoalPos = g.pos; directStart = c }
            }
            if (cost < gCost[s]) {
                gCost[s] = cost; prev[s] = -1; startPos[s] = c.pos
                pq.add(cost + h(s) to s)
            }
        }
        while (pq.isNotEmpty()) {
            val (f, s) = pq.poll()
            if (f >= bestTotal) break
            val g0 = gCost[s]
            if (f - h(s) > g0 + 1e-9) continue
            val edge = s / 2
            val v = exitNode(edge, s % 2 == 0)
            for (k2 in index.incident[v]) {
                val fwd2 = pack.from[k2] == v
                if (!fwd2 && (pack.to[k2] != v || pack.oneway(k2))) continue
                // Разворот на том же ребре — только в настоящем тупике: у края коридора дорога продолжается за обрезкой.
                if (k2 == edge && (index.degree[v] > 1 || pack.isBoundary(v))) continue
                if (!allowed(edge, v, k2)) continue
                val turn = abs(wrapAngle(travelBearing(k2, fwd2) - travelBearing(edge, s % 2 == 0)))
                // У ребра нулевой длины нет направления — поворот не определён, штраф не начисляется.
                val degenerate = index.length[edge] == 0.0 || index.length[k2] == 0.0
                val entry = g0 + when {
                    k2 == edge -> config.deadEndUturnPenaltyS                 // разворот в тупике (степень 1)
                    !degenerate && turn > PI / 4 -> config.turnPenaltyS
                    else -> 0.0
                }
                val s2 = state(k2, fwd2)
                val speed2 = pack.speedMps(k2)
                goalOf[s2]?.forEach { g ->
                    val total = entry + g.pos / speed2
                    if (total < bestTotal) {
                        // Предшественник финиша хранится отдельно: prev[s2] может позже смениться на путь,
                        // который проходит ребро целиком и к этому финишу не относится.
                        bestTotal = total; bestGoalState = s2; bestGoalPos = g.pos; bestGoalPrev = s; directStart = null
                    }
                }
                val g2 = entry + index.length[k2] / speed2
                if (g2 < gCost[s2]) {
                    gCost[s2] = g2; prev[s2] = s
                    pq.add(g2 + h(s2) to s2)
                }
            }
        }
        if (bestGoalState < 0) return null
        return build(bestGoalState, bestGoalPos, bestGoalPrev, directStart, prev, startPos, bestTotal)
    }

    private fun build(
        goal: Int, goalPos: Double, goalPrev: Int, direct: Cand?, prev: IntArray, startPos: DoubleArray, total: Double,
    ): Route {
        val chain = ArrayList<Int>()
        if (direct == null) {
            // Состояния раскрываются только после того, как их стоимость окончательна (A*), поэтому цепочка prev
            // от предшественника финиша — та самая, по которой считался bestTotal.
            var s = goalPrev
            while (s >= 0) { chain.add(s); s = prev[s] }
            chain.reverse()
        }
        chain.add(goal)
        val steps = chain.map { RouteStep(it / 2, it % 2 == 0) }
        val first = steps.first()
        val firstPos = direct?.pos ?: startPos[chain.first()]
        fun pointAt(step: RouteStep, pos: Double): DoubleArray {
            val a = if (step.forward) pack.from[step.edge] else pack.to[step.edge]
            val b = if (step.forward) pack.to[step.edge] else pack.from[step.edge]
            val len = index.length[step.edge]
            val f = if (len == 0.0) 0.0 else pos / len
            return doubleArrayOf(
                index.nodeE[a] + f * (index.nodeE[b] - index.nodeE[a]),
                index.nodeN[a] + f * (index.nodeN[b] - index.nodeN[a]),
            )
        }
        val points = ArrayList<DoubleArray>()
        points.add(pointAt(first, firstPos))
        for (i in 0 until steps.size - 1) {
            val v = exitNode(steps[i].edge, steps[i].forward)
            points.add(doubleArrayOf(index.nodeE[v], index.nodeN[v]))
        }
        points.add(pointAt(steps.last(), goalPos))
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
        }
        return Route(steps, points, cum, total)
    }
}
