package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ManeuversTest {
    private val enu = Enu(55.75, 37.60)

    private fun named(nodes: List<Pair<Double, Double>>, edges: List<EdgeSpec>, names: List<String?>): RoadPack {
        val base = roadPackOf(enu, nodes, edges)
        val table = names.filterNotNull().distinct()
        return RoadPack(base.lats, base.lons, base.way, base.from, base.to, base.flags, base.cls,
            nameIdx = IntArray(edges.size) { i -> names[i]?.let { table.indexOf(it) } ?: -1 }, names = table)
    }

    private fun types(ms: List<Maneuver>) = ms.map { it.type }

    @Test fun rightTurnAtJunctionIgnoresBends() {
        // Запад→восток с изломом 10° в узле степени 2, затем перекрёсток (степень 3): направо на юг.
        val nodes = listOf(0.0 to 0.0, 200.0 to 0.0, 400.0 to 35.0, 400.0 to -300.0, 400.0 to 300.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1), EdgeSpec(2, 3, 2), EdgeSpec(2, 4, 3))
        val p = named(nodes, edges, listOf("Улица А", "Улица А", "Улица Б", "Улица В"))
        val idx = RoadIndex(p, enu)
        val r = assertNotNull(Router(idx).route(0.0, 0.0, null, 400.0, -300.0))
        val ms = buildManeuvers(r, idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.RIGHT, ManeuverType.ARRIVE), types(ms))
        assertEquals("Улица Б", ms[1].street)
        assertEquals(r.cumM[2], ms[1].atM, 1e-6)
    }

    @Test fun slightAndSharpByAngle() {
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 150.0, 300.0 to 300.0, 600.0 to 0.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(1, 3, 3), EdgeSpec(1, 4, 4))
        val p = named(nodes, edges, listOf("А", "Б", "В", "Г"))
        val idx = RoadIndex(p, enu)
        val slight = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, 150.0)), idx)
        assertEquals(ManeuverType.SLIGHT_LEFT, slight[1].type)       // ≈27° влево
        val straight = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, 0.0)), idx)
        assertEquals(ManeuverType.CONTINUE, straight[1].type)        // прямо, сменилась улица
        assertEquals("Г", straight[1].street)
    }

    @Test fun roundaboutExitNumber() {
        // Квадратное кольцо против часовой стрелки: S(0,-50) → E(50,0) → N(0,50) → W(-50,0) → S.
        // Въезд с юга в S, съезды из E, N, W; маршрут выходит на север из N → второй съезд.
        val nodes = listOf(0.0 to -50.0, 50.0 to 0.0, 0.0 to 50.0, -50.0 to 0.0,
            0.0 to -300.0, 300.0 to 0.0, 0.0 to 300.0, -300.0 to 0.0)
        val ring = RoadPack.FLAG_ONEWAY or RoadPack.FLAG_ROUNDABOUT
        val edges = listOf(
            EdgeSpec(0, 1, 10, ring), EdgeSpec(1, 2, 10, ring), EdgeSpec(2, 3, 10, ring), EdgeSpec(3, 0, 10, ring),
            EdgeSpec(4, 0, 1), EdgeSpec(1, 5, 2), EdgeSpec(2, 6, 3), EdgeSpec(3, 7, 4),
        )
        val p = named(nodes, edges, listOf(null, null, null, null, "Юг", "Восток", "Север", "Запад"))
        val idx = RoadIndex(p, enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, -300.0, null, 0.0, 300.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ROUNDABOUT, ManeuverType.ARRIVE), types(ms))
        assertEquals(2, ms[1].exit); assertEquals("Север", ms[1].street)
    }

    @Test fun dualCarriagewayLeftTurnsMerge() {
        // Налево на перемычку (20 м) и сразу прямо на улицу Б — одна подсказка «налево — Б».
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 300.0 to 20.0, 300.0 to 300.0, 600.0 to 0.0, 300.0 to -300.0,
            600.0 to 20.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 3), EdgeSpec(1, 4, 4),
            EdgeSpec(1, 5, 5), EdgeSpec(2, 6, 6))
        val p = named(nodes, edges, listOf("А", "Перемычка", "Б", "Г", "Д", "Е"))
        val idx = RoadIndex(p, enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 300.0, 300.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.LEFT, ManeuverType.ARRIVE), types(ms))
        assertEquals("Б", ms[1].street)
    }
}
