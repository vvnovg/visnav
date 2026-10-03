package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteFollowerTest {
    private val enu = Enu(55.75, 37.60)

    /**
     * Восток по n=0 до перекрёстка (1000,0) (way 1); там направо на юг до (1000,−600) (way 2) или прямо на восток
     * до (1600,0) (way 3); от (1600,0) на юг (way 4) и по n=−600 обратно на запад к (1000,−600) (way 5) — квартал
     * замкнут, поэтому после пропущенного поворота есть путь к цели.
     */
    private fun graph(): RoadIndex {
        val nodes = ArrayList<Pair<Double, Double>>()
        fun add(e: Double, n: Double): Int { nodes += e to n; return nodes.size - 1 }
        val edges = ArrayList<EdgeSpec>()
        fun chain(ids: List<Int>, way: Long) { for (i in 0 until ids.size - 1) edges += EdgeSpec(ids[i], ids[i + 1], way) }
        val west = (0..10).map { add(100.0 * it, 0.0) }
        val south = listOf(west.last()) + (1..6).map { add(1000.0, -100.0 * it) }
        val east = listOf(west.last()) + (11..16).map { add(100.0 * it, 0.0) }
        val eastSouth = listOf(east.last()) + (1..6).map { add(1600.0, -100.0 * it) }
        val bottom = listOf(south.last()) + (11..15).map { add(100.0 * it, -600.0) } + listOf(eastSouth.last())
        chain(west, 1); chain(south, 2); chain(east, 3); chain(eastSouth, 4); chain(bottom, 5)
        return RoadIndex(roadPackOf(enu, nodes, edges), enu)
    }

    /** Прогон по точкам пути со скоростью 15 м/с, шаг 0,5 с. */
    private fun drive(f: RouteFollower, path: List<Pair<Double, Double>>, sigma: Double = 5.0): List<NavEvent> {
        val out = ArrayList<NavEvent>()
        var t = 0L
        for (k in 1 until path.size) {
            val (e0, n0) = path[k - 1]; val (e1, n1) = path[k]
            val len = kotlin.math.hypot(e1 - e0, n1 - n0)
            val psi = kotlin.math.atan2(e1 - e0, n1 - n0)
            var s = 0.0
            while (s < len) {
                out += f.update(t, e0 + (e1 - e0) * s / len, n0 + (n1 - n0) * s / len, sigma, psi, 15.0)
                s += 7.5; t += 500
            }
        }
        out += f.update(t, path.last().first, path.last().second, sigma, 0.0, 15.0)
        return out
    }

    @Test fun promptsInOrderOnceEachThenArrive() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val ev = drive(f, listOf(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to -500.0))
        val route = ev.filterIsInstance<NavEvent.RouteReady>()
        assertEquals(1, route.size); assertTrue(!route[0].reroute)
        val turn = route[0].maneuvers.indexOfFirst { it.type == ManeuverType.RIGHT }
        val prompts = ev.filterIsInstance<NavEvent.Prompt>().filter { it.maneuver == turn }
        assertEquals(listOf(PromptStage.FAR, PromptStage.NEAR, PromptStage.NOW), prompts.map { it.stage })
        assertTrue(prompts[0].distM in 300.0..450.0, "far at ${prompts[0].distM}")
        assertTrue(prompts[1].distM in 60.0..120.0, "near at ${prompts[1].distM}")
        assertTrue(prompts[2].distM <= 37.5)
        assertEquals("Поверните направо", prompts[2].text)
        assertEquals(1, ev.count { it is NavEvent.Arrived })
        assertTrue(ev.filterIsInstance<NavEvent.Prompt>().any { it.text == "Вы прибыли" })
    }

    @Test fun missedTurnReroutes() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val ev = drive(f, listOf(0.0 to 0.0, 1000.0 to 0.0, 1300.0 to 0.0))
        val rr = ev.filterIsInstance<NavEvent.RouteReady>()
        assertTrue(rr.size >= 2, "routes: ${rr.size}"); assertTrue(rr[1].reroute)
        assertTrue(rr[1].route.lengthM > 0)
    }

    @Test fun largeSigmaPreventsFalseReroute() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        // Едем параллельно маршруту в 35 м к северу с σ 20 м: порог max(40, 50) = 50 м — съезда нет.
        val ev = drive(f, listOf(0.0 to 35.0, 900.0 to 35.0), sigma = 20.0)
        assertEquals(1, ev.count { it is NavEvent.RouteReady })
    }

    @Test fun noGraphNearbyGivesRouteFailedRateLimited() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val ev = (0 until 40).flatMap { f.update(it * 500L, 5000.0, 5000.0, 5.0, 0.0, 10.0) }
        assertEquals(2, ev.count { it is NavEvent.RouteFailed })   // 0 с и 10 с за 20 с
    }
}
