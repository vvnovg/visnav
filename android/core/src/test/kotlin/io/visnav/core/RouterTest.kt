package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterTest {
    private val enu = Enu(55.75, 37.60)

    /** Узлы в метрах; рёбра (from, to, way, flags, cls, speedKmh); запреты по индексам рёбер. */
    private fun pack(
        nodes: List<Pair<Double, Double>>, edges: List<EdgeSpec>, speeds: List<Int>? = null,
        restrictions: List<TurnRestriction> = emptyList(),
    ): RoadPack {
        val base = roadPackOf(enu, nodes, edges)
        val sp = speeds?.let { s -> ByteArray(s.size) { s[it].toByte() } }
            ?: ByteArray(edges.size) { RoadClass.defaultSpeedKmh(edges[it].cls).toByte() }
        return RoadPack(base.lats, base.lons, base.way, base.from, base.to, base.flags, base.cls, sp,
            IntArray(edges.size) { -1 }, emptyList(), restrictions)
    }

    private fun ways(r: Route, p: RoadPack) = r.steps.map { p.way[it.edge] }.distinct()

    @Test fun prefersFasterRoadByTime() {
        // A(0,0) → B(1000,0) напрямую по двору 20 км/ч или через (500,400) по проспекту 60 км/ч (≈1280 м).
        val p = pack(listOf(0.0 to 0.0, 1000.0 to 0.0, 500.0 to 400.0),
            listOf(EdgeSpec(0, 1, 1), EdgeSpec(0, 2, 2, cls = RoadClass.PRIMARY), EdgeSpec(2, 1, 2, cls = RoadClass.PRIMARY)),
            speeds = listOf(20, 60, 60))
        val r = assertNotNull(Router(RoadIndex(p, enu)).route(0.0, 0.0, null, 1000.0, 0.0))
        assertEquals(listOf(2L), ways(r, p))
        assertTrue(r.durationS < 1000 / (20 / 3.6))
    }

    @Test fun respectsOnewayAndGoesAround() {
        // Прямая 0→1 односторонняя на запад (1→0); ехать на восток можно только через верхнюю петлю.
        val p = pack(listOf(0.0 to 0.0, 500.0 to 0.0, 0.0 to 200.0, 500.0 to 200.0),
            listOf(EdgeSpec(1, 0, 1, flags = RoadPack.FLAG_ONEWAY), EdgeSpec(0, 2, 2), EdgeSpec(2, 3, 3), EdgeSpec(3, 1, 4)))
        val r = assertNotNull(Router(RoadIndex(p, enu)).route(0.0, 0.0, null, 500.0, 0.0))
        assertEquals(listOf(2L, 3L, 4L), ways(r, p))
    }

    @Test fun noTurnRestrictionForcesAlternative() {
        // Крест в узле 1: запрещён поворот с ребра 0 (с запада) в ребро 2 (на север); есть объезд через восток.
        val nodes = listOf(-300.0 to 0.0, 0.0 to 0.0, 0.0 to 300.0, 300.0 to 0.0, 300.0 to 300.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 3, 1), EdgeSpec(1, 2, 2), EdgeSpec(3, 4, 3), EdgeSpec(4, 2, 4))
        val free = assertNotNull(Router(RoadIndex(pack(nodes, edges), enu)).route(-300.0, 0.0, null, 0.0, 300.0))
        assertEquals(listOf(1L, 2L), ways(free, pack(nodes, edges)))
        val restricted = pack(nodes, edges, restrictions = listOf(TurnRestriction(0, 1, 2, only = false)))
        val r = assertNotNull(Router(RoadIndex(restricted, enu)).route(-300.0, 0.0, null, 0.0, 300.0))
        assertEquals(listOf(1L, 3L, 4L), ways(r, restricted))
    }

    @Test fun onlyRestrictionAllowsOnlyItsTarget() {
        val nodes = listOf(-300.0 to 0.0, 0.0 to 0.0, 0.0 to 300.0, 300.0 to 0.0)
        // Ребро 1 одностороннее, чтобы нельзя было развернуться в тупике и вернуться к повороту.
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 3, 1, flags = RoadPack.FLAG_ONEWAY), EdgeSpec(1, 2, 2))
        val only = pack(nodes, edges, restrictions = listOf(TurnRestriction(0, 1, 1, only = true)))
        assertNull(Router(RoadIndex(only, enu)).route(-300.0, 0.0, null, 0.0, 300.0))
    }

    @Test fun startHeadingAvoidsDrivingAgainstIt() {
        // Двусторонняя прямая по n=0 (проспекты, 60 км/ч) и квартал через n=100; машина смотрит на восток,
        // цель — узел (300, 0) в 150 м позади. Объезд квартала: 650 м ≈ 54 с; «против курса»: 9 с + штраф 60 с.
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 0.0, 600.0 to 100.0, 300.0 to 100.0)
        val p = RoadClass.PRIMARY
        val edges = listOf(EdgeSpec(0, 1, 1, cls = p), EdgeSpec(1, 2, 1, cls = p), EdgeSpec(2, 3, 2, cls = p),
            EdgeSpec(3, 4, 3, cls = p), EdgeSpec(4, 1, 4, cls = p))
        val router = Router(RoadIndex(pack(nodes, edges), enu))
        val noHeading = assertNotNull(router.route(450.0, 0.0, null, 300.0, 0.0))
        assertTrue(!noHeading.steps.first().forward); assertEquals(150.0, noHeading.lengthM, 1e-6)
        val east = assertNotNull(router.route(450.0, 0.0, Math.PI / 2, 300.0, 0.0))
        assertTrue(east.steps.first().forward, "starts eastward")
        assertEquals(650.0, east.lengthM, 1e-6)
    }

    @Test fun pointsAndLengthAreConsistent() {
        val p = pack(listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0), listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2)))
        val r = assertNotNull(Router(RoadIndex(p, enu)).route(20.0, 3.0, null, 100.0, 70.0))
        assertEquals(3, r.points.size); assertEquals(80.0 + 70.0, r.lengthM, 1e-6)
        assertEquals(100.0, r.points[1][0], 1e-6); assertEquals(0.0, r.points[1][1], 1e-6)
    }

    @Test fun heuristicDoesNotOvershootOffRoadTarget() {
        // Цель T(0,−140) в 140 м от узла G(0,0); все рёбра 130 км/ч = maxSpeed, штраф за поворот 0 — время ∝ метрам.
        // Через Bn(0,500) с севера: 100 + 500 + 500 = 1100 м. Через An(500,−60): 100 + 560 + ≈487 = ≈1147 м.
        // Эвристика до T (а не до проекций финиша) завышена у Bn: 640 вместо 500 → f = 1240 > 1147, поиск
        // остановился бы на худшем маршруте.
        val nodes = listOf(500.0 to 600.0, 500.0 to 500.0, 0.0 to 500.0, 0.0 to 0.0, 500.0 to -60.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 3), EdgeSpec(1, 4, 4), EdgeSpec(4, 3, 5))
        val p = pack(nodes, edges, speeds = List(edges.size) { 130 })
        val r = assertNotNull(Router(RoadIndex(p, enu), RouterConfig(turnPenaltyS = 0.0)).route(500.0, 600.0, null, 0.0, -140.0))
        assertEquals(listOf(1L, 2L, 3L), ways(r, p))
        assertEquals(1100.0, r.lengthM, 1e-6)
    }

    @Test fun heuristicSpeedCoversFastestEdge() {
        // Та же раскладка, но maxSpeedMps = 5 м/с при рёбрах 130 км/ч: без защиты h завышена в разы.
        val nodes = listOf(500.0 to 600.0, 500.0 to 500.0, 0.0 to 500.0, 0.0 to 0.0, 500.0 to -60.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 3), EdgeSpec(1, 4, 4), EdgeSpec(4, 3, 5))
        val p = pack(nodes, edges, speeds = List(edges.size) { 130 })
        val cfg = RouterConfig(turnPenaltyS = 0.0, maxSpeedMps = 5.0)
        val r = assertNotNull(Router(RoadIndex(p, enu), cfg).route(500.0, 600.0, null, 0.0, -140.0))
        assertEquals(listOf(1L, 2L, 3L), ways(r, p))
    }

    @Test fun onlyRestrictionsFormUnionOfExits() {
        // Крест в узле 1, выходы — односторонние тупики (без разворота). С ребра 0: only → 1 и only → 2; выход 3 закрыт.
        val nodes = listOf(-300.0 to 0.0, 0.0 to 0.0, 300.0 to 0.0, 0.0 to 300.0, 0.0 to -300.0)
        val ow = RoadPack.FLAG_ONEWAY
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2, flags = ow), EdgeSpec(1, 3, 3, flags = ow),
            EdgeSpec(1, 4, 4, flags = ow))
        val free = Router(RoadIndex(pack(nodes, edges), enu))
        assertNotNull(free.route(-300.0, 0.0, null, 0.0, -300.0))
        val p = pack(nodes, edges, restrictions = listOf(TurnRestriction(0, 1, 1, only = true), TurnRestriction(0, 1, 2, only = true)))
        val router = Router(RoadIndex(p, enu))
        assertEquals(listOf(1L, 2L), ways(assertNotNull(router.route(-300.0, 0.0, null, 300.0, 0.0)), p))
        assertEquals(listOf(1L, 3L), ways(assertNotNull(router.route(-300.0, 0.0, null, 0.0, 300.0)), p))
        assertNull(router.route(-300.0, 0.0, null, 0.0, -300.0))
    }

    @Test fun goalBehindStartOnOnewayLoopsAround() {
        // Односторонний квадрат 400×200 против часовой: 0→1→2→3→0, петля 1200 м. Старт (300,0), цель (100,0) на том же
        // ребре в 200 м позади: маршрут — петля минус этот промежуток, 1000 м.
        val nodes = listOf(0.0 to 0.0, 400.0 to 0.0, 400.0 to 200.0, 0.0 to 200.0)
        val ow = RoadPack.FLAG_ONEWAY
        val edges = listOf(EdgeSpec(0, 1, 1, flags = ow), EdgeSpec(1, 2, 2, flags = ow), EdgeSpec(2, 3, 3, flags = ow),
            EdgeSpec(3, 0, 4, flags = ow))
        val r = assertNotNull(Router(RoadIndex(pack(nodes, edges), enu)).route(300.0, 0.0, null, 100.0, 0.0))
        assertEquals(RouteStep(0, true), r.steps.first()); assertEquals(RouteStep(0, true), r.steps.last())
        assertEquals(5, r.steps.size)
        assertEquals(1200.0 - 200.0, r.lengthM, 1e-6)
    }

    @Test fun uTurnAtDeadEndWhenHeadingAway() {
        // Двусторонняя дорога (0,0)→(300,0)→(350,0), тупик в (350,0). Машина в (250,0) смотрит на восток, цель (100,0)
        // позади. Против курса: 150 м ≈ 27 с + 60 с; через тупик: 50 + 50 + 50 + 200 = 350 м ≈ 63 с + 5 с за разворот.
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 350.0 to 0.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1))
        val r = assertNotNull(Router(RoadIndex(pack(nodes, edges), enu)).route(250.0, 0.0, Math.PI / 2, 100.0, 0.0))
        assertEquals(listOf(RouteStep(0, true), RouteStep(1, true), RouteStep(1, false), RouteStep(0, false)), r.steps)
        assertEquals(350.0, r.lengthM, 1e-6)
    }

    @Test fun startExactlyAtNode() {
        // Старт ровно в узле (100,0), где сходятся три дороги; цель — конец ветки на север.
        val nodes = listOf(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 100.0 to 100.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(1, 3, 3))
        val r = assertNotNull(Router(RoadIndex(pack(nodes, edges), enu)).route(100.0, 0.0, null, 100.0, 100.0))
        assertEquals(listOf(RouteStep(2, true)), r.steps)
        assertEquals(100.0, r.points[0][0], 1e-6); assertEquals(0.0, r.points[0][1], 1e-6)
        assertEquals(100.0, r.lengthM, 1e-6)
    }

    @Test fun zeroLengthEdgeAddsNoTurnPenalty() {
        // Узлы 1 и 2 совпадают (ребро нулевой длины); прямая 200 м по двору 20 км/ч — ровно 36 с без штрафов.
        val nodes = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1), EdgeSpec(2, 3, 1))
        val r = assertNotNull(Router(RoadIndex(pack(nodes, edges), enu)).route(0.0, 0.0, null, 200.0, 0.0))
        assertEquals(200.0, r.lengthM, 1e-6)
        assertEquals(200.0 / (20 / 3.6), r.durationS, 1e-6)
    }

    @Test fun unreachableOrOffGraphReturnsNull() {
        val p = pack(listOf(0.0 to 0.0, 100.0 to 0.0), listOf(EdgeSpec(0, 1, 1)))
        val router = Router(RoadIndex(p, enu))
        assertNull(router.route(50.0, 0.0, null, 5000.0, 5000.0))
        assertNull(router.route(5000.0, 5000.0, null, 50.0, 0.0))
    }

    // Две односторонние проезжие части в 15 м друг от друга; разрывы — только на концах (2 км).
    private fun dual(): RoadIndex {
        val nodes = listOf(0.0 to 0.0, 2000.0 to 0.0, 2000.0 to 15.0, 0.0 to 15.0)
        val edges = listOf(EdgeSpec(0, 1, 1, RoadPack.FLAG_ONEWAY), EdgeSpec(1, 2, 3), EdgeSpec(2, 3, 2, RoadPack.FLAG_ONEWAY))
        return RoadIndex(pack(nodes, edges), enu)
    }

    @Test fun startWithHeadingStaysOnCarriagewayItPointsAlong() {
        // Цель на встречной части в 100 м позади: ближайший путь — по встречной, но едем на восток.
        val r = assertNotNull(Router(dual()).route(500.0, 2.0, Math.PI / 2, 100.0, 15.0))
        assertTrue(r.steps[0].edge == 0 && r.steps[0].forward)
    }

    @Test fun startWithoutHeadingPicksNearerCarriageway() {
        val r = assertNotNull(Router(dual()).route(500.0, 2.0, null, 100.0, 15.0))
        assertTrue(r.steps[0].edge == 0 && r.steps[0].forward)
    }
}
