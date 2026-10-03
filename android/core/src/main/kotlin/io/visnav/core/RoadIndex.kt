package io.visnav.core

import java.util.PriorityQueue
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/** Проекция точки на ребро: t ∈ [0, 1] от from к to, (e, n) — ближайшая точка ребра, distM — расстояние до неё. */
data class RoadProjection(val edge: Int, val t: Double, val e: Double, val n: Double, val distM: Double)

/**
 * Граф в плоских координатах фильтра [enu]: сетка ячеек для поиска ближайших рёбер и направленные
 * смежности для путей по графу (по одностороннему ребру — только from → to).
 */
class RoadIndex(val pack: RoadPack, val enu: Enu, private val cellM: Double = 50.0) {
    val nodeE = DoubleArray(pack.nodeCount)
    val nodeN = DoubleArray(pack.nodeCount)
    val length = DoubleArray(pack.edgeCount)
    /** Азимут ребра from → to, рад от севера по часовой. */
    val bearing = DoubleArray(pack.edgeCount)
    /** Число рёбер, сходящихся в узле (без учёта направления). */
    val degree = IntArray(pack.nodeCount)
    /** Расстояние по графу (без учёта направления) до ближайшего узла со степенью ≠ 2; дальше 200 м — бесконечность. */
    val junctionDist = DoubleArray(pack.nodeCount) { Double.POSITIVE_INFINITY }
    /** Рёбра, касающиеся узла, независимо от направления (петля — один раз). */
    val incident: Array<IntArray>
    val outNodes: Array<IntArray>
    val outLen: Array<DoubleArray>
    private val grid = HashMap<Long, MutableList<Int>>()

    init {
        for (i in 0 until pack.nodeCount) {
            val en = enu.toEn(pack.lats[i], pack.lons[i]); nodeE[i] = en[0]; nodeN[i] = en[1]
        }
        val inc = Array(pack.nodeCount) { ArrayList<Int>() }
        val outN = Array(pack.nodeCount) { ArrayList<Int>() }
        val outL = Array(pack.nodeCount) { ArrayList<Double>() }
        for (k in 0 until pack.edgeCount) {
            val a = pack.from[k]; val b = pack.to[k]
            val de = nodeE[b] - nodeE[a]; val dn = nodeN[b] - nodeN[a]
            length[k] = hypot(de, dn); bearing[k] = atan2(de, dn)
            degree[a]++; degree[b]++
            inc[a].add(k); if (b != a) inc[b].add(k)
            outN[a].add(b); outL[a].add(length[k])
            if (!pack.oneway(k)) { outN[b].add(a); outL[b].add(length[k]) }
            for (cx in cell(minOf(nodeE[a], nodeE[b]))..cell(maxOf(nodeE[a], nodeE[b]))) {
                for (cy in cell(minOf(nodeN[a], nodeN[b]))..cell(maxOf(nodeN[a], nodeN[b]))) {
                    grid.getOrPut(key(cx, cy)) { ArrayList() }.add(k)
                }
            }
        }
        val und = Array(pack.nodeCount) { ArrayList<Pair<Int, Double>>() }
        for (k in 0 until pack.edgeCount) {
            und[pack.from[k]].add(pack.to[k] to length[k]); und[pack.to[k]].add(pack.from[k] to length[k])
        }
        val jq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        for (i in 0 until pack.nodeCount) if (degree[i] != 2) { junctionDist[i] = 0.0; jq.add(0.0 to i) }
        while (jq.isNotEmpty()) {
            val (d, u) = jq.poll()
            if (d > junctionDist[u]) continue
            for ((v, l) in und[u]) {
                val nd = d + l
                if (nd <= JUNCTION_SEARCH_M && nd < junctionDist[v]) { junctionDist[v] = nd; jq.add(nd to v) }
            }
        }
        incident = Array(pack.nodeCount) { inc[it].toIntArray() }
        outNodes = Array(pack.nodeCount) { outN[it].toIntArray() }
        outLen = Array(pack.nodeCount) { outL[it].toDoubleArray() }
    }

    private companion object { const val JUNCTION_SEARCH_M = 200.0 }

    private fun cell(v: Double): Int = floor(v / cellM).toInt()
    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    fun project(edge: Int, e: Double, n: Double): RoadProjection {
        val a = pack.from[edge]; val b = pack.to[edge]
        val de = nodeE[b] - nodeE[a]; val dn = nodeN[b] - nodeN[a]
        val len2 = de * de + dn * dn
        val t = if (len2 == 0.0) 0.0 else (((e - nodeE[a]) * de + (n - nodeN[a]) * dn) / len2).coerceIn(0.0, 1.0)
        val pe = nodeE[a] + t * de; val pn = nodeN[a] + t * dn
        return RoadProjection(edge, t, pe, pn, hypot(e - pe, n - pn))
    }

    /** Рёбра ближе radiusM к точке, по возрастанию расстояния. */
    fun near(e: Double, n: Double, radiusM: Double): List<RoadProjection> {
        val seen = HashSet<Int>()
        val out = ArrayList<RoadProjection>()
        for (cx in cell(e - radiusM)..cell(e + radiusM)) for (cy in cell(n - radiusM)..cell(n + radiusM)) {
            val edges = grid[key(cx, cy)] ?: continue
            for (k in edges) if (seen.add(k)) {
                val p = project(k, e, n)
                if (p.distM <= radiusM) out.add(p)
            }
        }
        out.sortBy { it.distM }
        return out
    }

    /** Кратчайшие расстояния по графу из node с учётом односторонних, не дальше limitM (Дейкстра). */
    fun routeFrom(node: Int, limitM: Double): Map<Int, Double> {
        val dist = HashMap<Int, Double>()
        dist[node] = 0.0
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        pq.add(0.0 to node)
        while (pq.isNotEmpty()) {
            val (d, u) = pq.poll()
            if (d > (dist[u] ?: Double.MAX_VALUE)) continue
            val ns = outNodes[u]; val ls = outLen[u]
            for (i in ns.indices) {
                val nd = d + ls[i]
                if (nd > limitM) continue
                if (nd < (dist[ns[i]] ?: Double.MAX_VALUE)) { dist[ns[i]] = nd; pq.add(nd to ns[i]) }
            }
        }
        return dist
    }
}
