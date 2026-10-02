package io.visnav.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

data class MatchConfig(
    val searchRadiusM: Double = 50.0,
    val maxCandidates: Int = 8,
    val minEmissionSigmaM: Double = 5.0,
    val headingSigmaDeg: Double = 30.0,
    val minHeadingSpeedMps: Double = 3.0,
    val betaM: Double = 10.0,
    /** Штраф перехода, если по графу пути нет: позволяет выйти с ошибочно выбранной несвязанной улицы. */
    val teleportPenalty: Double = 20.0,
    val junctionM: Double = 20.0,
)

/** Результат шага: ребро и направление движения по нему (travelBearing), точка на оси дороги, уверенность ∈ [0, 1]. */
data class RoadMatch(
    val edge: Int, val wayId: Long, val e: Double, val n: Double, val distM: Double,
    val travelBearing: Double, val confidence: Double, val roadClass: Int, val tunnel: Boolean,
    val nearJunction: Boolean,
)

/**
 * Онлайн-привязка к дорогам: HMM по рёбрам графа (Newson–Krumm), прямой проход Витерби без задержки.
 * Состояние — ребро и направление движения (по одностороннему ребру — только вперёд). Эмиссия — расстояние
 * до ребра и расхождение курса; переход — |путь по графу − пройденное фильтром расстояние| / β.
 */
class MapMatcher(private val index: RoadIndex, private val config: MatchConfig = MatchConfig()) {
    private class State(val proj: RoadProjection, val forward: Boolean, val score: Double)

    private var prev: List<State> = emptyList()
    private var prevE = 0.0
    private var prevN = 0.0

    fun reset() { prev = emptyList() }

    fun step(e: Double, n: Double, sigmaM: Double, psi: Double, speedMps: Double): RoadMatch? {
        val projections = index.near(e, n, config.searchRadiusM).take(config.maxCandidates)
        if (projections.isEmpty()) { reset(); return null }
        val sigE = max(sigmaM, config.minEmissionSigmaM)
        val sigPsi = Math.toRadians(config.headingSigmaDeg)
        val useHeading = speedMps >= config.minHeadingSpeedMps
        val trav = hypot(e - prevE, n - prevN)
        val limit = 2 * trav + 50.0
        val cache = HashMap<Int, Map<Int, Double>>()
        val cand = ArrayList<State>()
        val emission = ArrayList<Double>()
        val viaPrev = ArrayList<Double>()
        for (p in projections) for (forward in booleanArrayOf(true, false)) {
            if (!forward && index.pack.oneway(p.edge)) continue
            val z = p.distM / sigE
            var em = -0.5 * z * z
            if (useHeading) {
                val d = wrapAngle(psi - travelBearing(p.edge, forward)) / sigPsi
                em -= 0.5 * d * d
            }
            var best = Double.NEGATIVE_INFINITY
            for (s in prev) {
                val r = route(s, p, forward, cache, limit)
                val tr = if (r == null) -config.teleportPenalty else -abs(r - trav) / config.betaM
                best = max(best, s.score + tr)
            }
            cand.add(State(p, forward, 0.0)); emission.add(em); viaPrev.add(best)
        }
        // Нет предыдущих состояний (начало или потеря дорог) — цепочка начинается заново.
        val restart = prev.isEmpty()
        val scores = DoubleArray(cand.size) { if (restart) emission[it] else viaPrev[it] + emission[it] }
        val top = scores.max()
        val states = cand.indices.filter { scores[it] - top > PRUNE }
            .map { State(cand[it].proj, cand[it].forward, scores[it] - top) }
        prev = states; prevE = e; prevN = n

        val best = states.maxBy { it.score }
        val way = index.pack.way[best.proj.edge]
        var total = 0.0
        var sameWay = 0.0
        for (s in states) {
            val w = exp(s.score)
            total += w
            if (index.pack.way[s.proj.edge] == way) sameWay += w
        }
        val p = best.proj
        return RoadMatch(
            p.edge, way, p.e, p.n, p.distM, travelBearing(p.edge, best.forward), sameWay / total,
            index.pack.roadClass(p.edge), index.pack.tunnel(p.edge), nearJunction(p),
        )
    }

    private fun travelBearing(edge: Int, forward: Boolean): Double =
        if (forward) index.bearing[edge] else wrapAngle(index.bearing[edge] + PI)

    private fun along(t: Double, len: Double, forward: Boolean): Double = if (forward) t * len else (1 - t) * len

    /** Путь по графу от состояния s до проекции p при движении forward; null — пути нет в пределах limit. */
    private fun route(
        s: State, p: RoadProjection, forward: Boolean, cache: HashMap<Int, Map<Int, Double>>, limit: Double,
    ): Double? {
        val sLen = index.length[s.proj.edge]
        val sPos = along(s.proj.t, sLen, s.forward)
        if (s.proj.edge == p.edge && s.forward == forward) {
            val d = along(p.t, sLen, forward) - sPos
            return if (d >= -BACKTRACK_TOLERANCE_M) abs(d) else null
        }
        val exit = if (s.forward) index.pack.to[s.proj.edge] else index.pack.from[s.proj.edge]
        val entry = if (forward) index.pack.from[p.edge] else index.pack.to[p.edge]
        val mid = cache.getOrPut(exit) { index.routeFrom(exit, limit) }[entry] ?: return null
        return (sLen - sPos) + mid + along(p.t, index.length[p.edge], forward)
    }

    private fun nearJunction(p: RoadProjection): Boolean {
        val a = index.pack.from[p.edge]; val b = index.pack.to[p.edge]
        val len = index.length[p.edge]
        return (index.degree[a] != 2 && p.t * len <= config.junctionM) ||
            (index.degree[b] != 2 && (1 - p.t) * len <= config.junctionM)
    }

    private companion object {
        const val BACKTRACK_TOLERANCE_M = 5.0
        const val PRUNE = -30.0
    }
}
