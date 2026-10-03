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
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2, cls = RoadClass.SECONDARY), EdgeSpec(1, 3, 3),
            EdgeSpec(1, 4, 4, cls = RoadClass.SECONDARY))
        val p = named(nodes, edges, listOf("А", "Б", "В", "Г"))
        val idx = RoadIndex(p, enu)
        val slight = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, 150.0)), idx)
        assertEquals(ManeuverType.SLIGHT_LEFT, slight[1].type)       // ≈27° влево
        // Прямо на Г (класс 4): Б того же класса уходит на ≈27° левее, т.е. не менее важен — развилка, маршрут правее него.
        val straight = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, 0.0)), idx)
        assertEquals(ManeuverType.SLIGHT_RIGHT, straight[1].type)
        assertEquals("Г", straight[1].street)
    }

    @Test fun continueOnlyWhenStreetNameChangesToNonNull() {
        // Боковая улица под 90° — не конкурент; прямо.
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 0.0, 300.0 to 300.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(1, 3, 3))
        val renamed = RoadIndex(named(nodes, edges, listOf("А", "Б", "В")), enu)
        val ms = buildManeuvers(assertNotNull(Router(renamed).route(0.0, 0.0, null, 600.0, 0.0)), renamed)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.CONTINUE, ManeuverType.ARRIVE), types(ms))
        val same = RoadIndex(named(nodes, edges, listOf("А", "А", "В")), enu)
        assertEquals(2, buildManeuvers(assertNotNull(Router(same).route(0.0, 0.0, null, 600.0, 0.0)), same).size)
        val unnamed = RoadIndex(named(nodes, edges, listOf("А", null, "В")), enu)
        assertEquals(2, buildManeuvers(assertNotNull(Router(unnamed).route(0.0, 0.0, null, 600.0, 0.0)), unnamed).size)
    }

    @Test fun straightOnPrimaryPastMinorSideStreetIsNoManeuver() {
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 0.0, 600.0 to 150.0)   // боковая под 26.6° влево
        val edges = listOf(EdgeSpec(0, 1, 1, cls = RoadClass.PRIMARY), EdgeSpec(1, 2, 1, cls = RoadClass.PRIMARY),
            EdgeSpec(1, 3, 2, cls = RoadClass.RESIDENTIAL))
        val idx = RoadIndex(named(nodes, edges, listOf("А", "А", "Б")), enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, 0.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), types(ms))
    }

    @Test fun forkOfTwoUnnamedBranches() {
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 52.9, 600.0 to -52.9)   // ветки ±10° от курса
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(1, 3, 3))
        val idx = RoadIndex(named(nodes, edges, listOf(null, null, null)), enu)
        val right = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, -52.9)), idx)
        assertEquals(ManeuverType.SLIGHT_RIGHT, right[1].type)
        val left = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, 52.9)), idx)
        assertEquals(ManeuverType.SLIGHT_LEFT, left[1].type)
    }

    @Test fun bendAtNodeWithInboundOnlyThirdEdgeIsNotAManeuver() {
        // Излом 30° влево; третье ребро односторонне В узел (под 10° влево от курса) — выехать по нему нельзя.
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 560.0 to 150.0, 600.0 to 52.9)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1), EdgeSpec(3, 1, 2, RoadPack.FLAG_ONEWAY))
        val idx = RoadIndex(named(nodes, edges, listOf("А", "А", "Б")), enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 560.0, 150.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), types(ms))
    }

    @Test fun namedMotorwayWithUnnamedLinkIsFork() {
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 0.0, 600.0 to -63.8)   // съезд 12° вправо
        val edges = listOf(EdgeSpec(0, 1, 1, cls = RoadClass.MOTORWAY), EdgeSpec(1, 2, 1, cls = RoadClass.MOTORWAY),
            EdgeSpec(1, 3, 2, RoadPack.FLAG_LINK, RoadClass.MOTORWAY))   // съезд (_link): класс магистрали
        val idx = RoadIndex(named(nodes, edges, listOf("МКАД", "МКАД", null)), enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, -63.8)), idx)
        assertEquals(ManeuverType.SLIGHT_RIGHT, ms[1].type)
    }

    @Test fun zeroLengthWindowAtStartNodeFallsBackToEdgeBearing() {
        // Старт ровно в узле перекрёстка (t = 1 на первом ребре): окно входа нулевое. Прямо — манёвра нет.
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 0.0, 300.0 to 300.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1), EdgeSpec(1, 3, 2))
        val idx = RoadIndex(named(nodes, edges, listOf("А", "А", "Б")), enu)
        val r = Route(
            listOf(RouteStep(0, true), RouteStep(1, true)),
            listOf(doubleArrayOf(300.0, 0.0), doubleArrayOf(300.0, 0.0), doubleArrayOf(600.0, 0.0)),
            doubleArrayOf(0.0, 0.0, 300.0), 0.0)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), types(buildManeuvers(r, idx)))
    }

    @Test fun zeroLengthWindowAtGoalNodeFallsBackToEdgeBearing() {
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 0.0, 300.0 to 300.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1), EdgeSpec(1, 3, 2))
        val idx = RoadIndex(named(nodes, edges, listOf("А", "А", "Б")), enu)
        val r = Route(
            listOf(RouteStep(0, true), RouteStep(1, true)),
            listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(300.0, 0.0), doubleArrayOf(300.0, 0.0)),
            doubleArrayOf(0.0, 300.0, 300.0), 0.0)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), types(buildManeuvers(r, idx)))
    }

    @Test fun rivalIsMeasuredAlongItsOwnGeometry() {
        // Основная дорога: 5 м под 6° вправо, затем уходит влево (за 25 м в среднем ≈14° влево); съезд — 4° вправо.
        // По первому сегменту съезд был бы левее основной (4° < 6°) → SLIGHT_LEFT; по окну 25 м он правее → SLIGHT_RIGHT.
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 304.973 to -0.523, 600.0 to 100.0, 399.76 to -6.976)
        val edges = listOf(EdgeSpec(0, 1, 1, cls = RoadClass.MOTORWAY), EdgeSpec(1, 2, 2, cls = RoadClass.MOTORWAY),
            EdgeSpec(2, 3, 2, cls = RoadClass.MOTORWAY), EdgeSpec(1, 4, 3, RoadPack.FLAG_LINK, RoadClass.MOTORWAY))
        val idx = RoadIndex(named(nodes, edges, listOf(null, null, null, null)), enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 399.76, -6.976)), idx)
        assertEquals(ManeuverType.SLIGHT_RIGHT, ms[1].type)
    }

    @Test fun straightPastLinkIsNoManeuver() {
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 0.0, 600.0 to -63.8)   // съезд 12° вправо
        val edges = listOf(EdgeSpec(0, 1, 1, cls = RoadClass.MOTORWAY), EdgeSpec(1, 2, 1, cls = RoadClass.MOTORWAY),
            EdgeSpec(1, 3, 2, RoadPack.FLAG_LINK, RoadClass.MOTORWAY))
        val idx = RoadIndex(named(nodes, edges, listOf("МКАД", "МКАД", null)), enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, 0.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), types(ms))
    }

    @Test fun mergedTurnsOf90And50AreNotAUTurn() {
        // Налево 90° и через 20 м ещё налево ≈50°: общий поворот ≈140° (< 150), а не сумма углов с окнами.
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 300.0 to 20.0, 70.2 to 212.8, 600.0 to 0.0, 600.0 to 20.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 3), EdgeSpec(1, 4, 1), EdgeSpec(2, 5, 3))
        val idx = RoadIndex(named(nodes, edges, listOf("А", "Перемычка", "Б", "А", "Б")), enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 70.2, 212.8)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.LEFT, ManeuverType.ARRIVE), types(ms))
        assertEquals(-140.0, ms[1].angleDeg, 1.0)
    }

    @Test fun uTurnThroughMedianGap() {
        // Налево в разрыв 15 м и снова налево на встречную проезжую часть — один разворот.
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 300.0 to 15.0, -100.0 to 15.0, 600.0 to 0.0, 600.0 to 15.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 3), EdgeSpec(1, 4, 1), EdgeSpec(2, 5, 3))
        val idx = RoadIndex(named(nodes, edges, listOf("А", "Разрыв", "Б", "А", "Б")), enu)
        val router = Router(idx, RouterConfig(snapRadiusM = 5.0, destSnapRadiusM = 5.0))
        val ms = buildManeuvers(assertNotNull(router.route(0.0, 0.0, null, -100.0, 15.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.UTURN, ManeuverType.ARRIVE), types(ms))
        assertEquals("Б", ms[1].street)
    }

    @Test fun roundaboutSplitArmDoesNotCountEntryOnlyNode() {
        // Восточный подъезд расщеплён: въезд-only в Ein, съезд-only из Eout, 12 м по кольцу. Выход на север — 2-й.
        val nodes = listOf(0.0 to -50.0, 49.6 to -6.1, 49.6 to 6.1, 0.0 to 50.0, -50.0 to 0.0,
            0.0 to -300.0, 300.0 to -6.1, 300.0 to 6.1, 0.0 to 300.0, -300.0 to 0.0)
        val ring = RoadPack.FLAG_ONEWAY or RoadPack.FLAG_ROUNDABOUT
        val edges = listOf(
            EdgeSpec(0, 1, 10, ring), EdgeSpec(1, 2, 10, ring), EdgeSpec(2, 3, 10, ring), EdgeSpec(3, 4, 10, ring),
            EdgeSpec(4, 0, 10, ring),
            EdgeSpec(5, 0, 1), EdgeSpec(6, 1, 2, RoadPack.FLAG_ONEWAY), EdgeSpec(2, 7, 3, RoadPack.FLAG_ONEWAY),
            EdgeSpec(3, 8, 4), EdgeSpec(4, 9, 5),
        )
        val idx = RoadIndex(named(nodes, edges, listOf(null, null, null, null, null, "Юг", "ВхВ", "ВыхВ", "Север", "Запад")), enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, -300.0, null, 0.0, 300.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ROUNDABOUT, ManeuverType.ARRIVE), types(ms))
        assertEquals(2, ms[1].exit); assertEquals("Север", ms[1].street)
    }

    @Test fun routeStartingOrEndingInsideRingHasNoRoundaboutManeuver() {
        val nodes = listOf(0.0 to -50.0, 50.0 to 0.0, 0.0 to 50.0, -50.0 to 0.0,
            0.0 to -300.0, 300.0 to 0.0, 0.0 to 300.0, -300.0 to 0.0)
        val ring = RoadPack.FLAG_ONEWAY or RoadPack.FLAG_ROUNDABOUT
        val edges = listOf(
            EdgeSpec(0, 1, 10, ring), EdgeSpec(1, 2, 10, ring), EdgeSpec(2, 3, 10, ring), EdgeSpec(3, 0, 10, ring),
            EdgeSpec(4, 0, 1), EdgeSpec(1, 5, 2), EdgeSpec(2, 6, 3), EdgeSpec(3, 7, 4),
        )
        val idx = RoadIndex(named(nodes, edges, listOf(null, null, null, null, "Юг", "Восток", "Север", "Запад")), enu)
        val router = Router(idx, RouterConfig(snapRadiusM = 5.0, destSnapRadiusM = 5.0))
        // Старт на кольце → съезд на север.
        val fromRing = buildManeuvers(assertNotNull(router.route(25.0, -25.0, null, 0.0, 300.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), types(fromRing))
        // Финиш на кольце.
        val toRing = buildManeuvers(assertNotNull(router.route(0.0, -300.0, null, 25.0, 25.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), types(toRing))
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
