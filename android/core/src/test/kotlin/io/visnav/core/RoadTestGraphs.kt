package io.visnav.core

/** Ребро тестового графа: индексы узлов, wayId, флаги RoadPack.FLAG_*, класс RoadClass. */
data class EdgeSpec(val from: Int, val to: Int, val way: Long, val flags: Int = 0, val cls: Int = RoadClass.RESIDENTIAL)

/** Тестовый граф: узлы заданы в метрах (восток, север) относительно enu. */
fun roadPackOf(enu: Enu, nodes: List<Pair<Double, Double>>, edges: List<EdgeSpec>): RoadPack {
    val lats = DoubleArray(nodes.size)
    val lons = DoubleArray(nodes.size)
    nodes.forEachIndexed { i, (e, n) -> val ll = enu.toLatLon(e, n); lats[i] = ll[0]; lons[i] = ll[1] }
    return RoadPack(
        lats, lons, LongArray(edges.size) { edges[it].way }, IntArray(edges.size) { edges[it].from },
        IntArray(edges.size) { edges[it].to }, ByteArray(edges.size) { edges[it].flags.toByte() },
        ByteArray(edges.size) { edges[it].cls.toByte() },
    )
}

/**
 * Прямой участок дороги от (e0, n0) до (e1, n1) с узлами через stepM, один wayId. Узлы нумеруются с
 * nodeOffset — так несколько участков склеиваются в один граф (общие узлы передаются через startNode).
 */
fun straightRoad(
    e0: Double, n0: Double, e1: Double, n1: Double, stepM: Double, way: Long, nodeOffset: Int,
    flags: Int = 0, cls: Int = RoadClass.RESIDENTIAL, startNode: Int? = null,
): Pair<List<Pair<Double, Double>>, List<EdgeSpec>> {
    val len = kotlin.math.hypot(e1 - e0, n1 - n0)
    val k = maxOf(1, kotlin.math.round(len / stepM).toInt())
    val nodes = ArrayList<Pair<Double, Double>>()
    val ids = ArrayList<Int>()
    for (i in 0..k) {
        if (i == 0 && startNode != null) { ids.add(startNode); continue }
        nodes.add(Pair(e0 + (e1 - e0) * i / k, n0 + (n1 - n0) * i / k))
        ids.add(nodeOffset + nodes.size - 1)
    }
    val edges = (0 until k).map { EdgeSpec(ids[it], ids[it + 1], way, flags, cls) }
    return nodes to edges
}
