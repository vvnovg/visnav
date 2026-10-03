package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
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

    /** Пошаговый прогон с явным временем: шаг 0,5 с; path — точки через 7,5 м (15 м/с), без последней точки. */
    private class Run(val f: RouteFollower) {
        var t = 0L
        val ev = ArrayList<NavEvent>()
        val progress = ArrayList<Double>()
        fun at(e: Double, n: Double, sigma: Double = 5.0, psi: Double = 0.0, v: Double = 15.0) {
            ev += f.update(t, e, n, sigma, psi, v); progress += f.progressM; t += 500
        }
        fun path(pts: List<Pair<Double, Double>>, sigma: Double = 5.0) {
            for (k in 1 until pts.size) {
                val (e0, n0) = pts[k - 1]; val (e1, n1) = pts[k]
                val len = kotlin.math.hypot(e1 - e0, n1 - n0)
                val psi = kotlin.math.atan2(e1 - e0, n1 - n0)
                var s = 0.0
                while (s < len) { at(e0 + (e1 - e0) * s / len, n0 + (n1 - n0) * s / len, sigma, psi); s += 7.5 }
            }
        }
        fun park(e: Double, n: Double, seconds: Int) = repeat(seconds * 2) { at(e, n, v = 0.0) }
    }

    @Test fun failedRerouteKeepsOldRouteAndRetries() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val r = Run(f)
        // Уходим с дороги по диагонали дальше радиуса привязки (60 м): перестроить нельзя.
        r.path(listOf(0.0 to 0.0, 850.0 to 0.0, 990.0 to 200.0))
        val original = f.route!!
        val turn = f.maneuvers.indexOfFirst { it.type == ManeuverType.RIGHT }
        r.park(990.0, 200.0, 25)
        assertTrue(r.ev.count { it is NavEvent.RouteFailed } >= 2, "failed: ${r.ev.count { it is NavEvent.RouteFailed }}")
        assertSame(original, f.route)
        // Вне маршрута подсказок нет: «сейчас» для поворота не звучала, хотя проекция прошла порог 37,5 м.
        assertTrue(r.ev.filterIsInstance<NavEvent.Prompt>().none { it.maneuver == turn && it.stage != PromptStage.FAR })
        // Вернулись к графу (50 м от дороги, ещё вне маршрута) — повтор после паузы удаётся, это перестроение.
        r.park(990.0, 50.0, 12)
        val rr = r.ev.filterIsInstance<NavEvent.RouteReady>()
        assertEquals(2, rr.size); assertTrue(rr[1].reroute)
        assertNotSame(original, f.route)
    }

    @Test fun startFarFromRoadParkedNoRerouteNoPrompts() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val r = Run(f)
        r.park(800.0, 50.0, 60)        // стоянка в 50 м от дороги
        assertEquals(1, r.ev.count { it is NavEvent.RouteReady })
        assertEquals(0, r.ev.count { it is NavEvent.Prompt })
        assertEquals(0, r.ev.count { it is NavEvent.RouteFailed })
        // Выехали на дорогу — маршрут подхвачен, подсказки пошли, перестроений нет.
        r.path(listOf(800.0 to 50.0, 840.0 to 0.0, 1000.0 to 0.0))
        val turn = f.maneuvers.indexOfFirst { it.type == ManeuverType.RIGHT }
        assertTrue(r.ev.filterIsInstance<NavEvent.Prompt>().any { it.maneuver == turn && it.stage == PromptStage.NEAR })
        assertEquals(1, r.ev.count { it is NavEvent.RouteReady })
    }

    /**
     * Подъезд на восток по n=0 до развилки (500,0); петля 270° по часовой (центр (500,−30), r 30 м, ~141 м) выходит
     * на север по e=470 и пересекает подъезд путепроводом (без общего узла) у (470,0).
     */
    private fun loopGraph(): RoadIndex {
        val nodes = ArrayList<Pair<Double, Double>>()
        fun add(e: Double, n: Double): Int { nodes += e to n; return nodes.size - 1 }
        val edges = ArrayList<EdgeSpec>()
        fun chain(ids: List<Int>, way: Long) { for (i in 0 until ids.size - 1) edges += EdgeSpec(ids[i], ids[i + 1], way) }
        val approach = (0..7).map { add(100.0 * it, 0.0) }
        val loop = (1..27).map { k ->
            val th = Math.toRadians(90.0 - 10.0 * k)
            add(500.0 + 30 * kotlin.math.cos(th), -30.0 + 30 * kotlin.math.sin(th))
        }
        val north = listOf(-10.0, 10.0, 100.0, 200.0, 300.0).map { add(470.0, it) }
        chain(approach, 1); chain(listOf(approach[5]) + loop + north, 2)
        return RoadIndex(roadPackOf(enu, nodes, edges), enu)
    }

    @Test fun selfCrossingLoopKeepsProgressContinuous() {
        val f = RouteFollower(loopGraph(), 470.0, 250.0)
        val r = Run(f)
        val loop = (1..27).map { k ->
            val th = Math.toRadians(90.0 - 10.0 * k)
            500.0 + 30 * kotlin.math.cos(th) to -30.0 + 30 * kotlin.math.sin(th)
        }
        // Подъезд в 3 м севернее оси: у пересечения (472,5; 3) северная ветка (2,5 м) ближе подъезда (3 м).
        r.path(listOf(0.0 to 3.0, 500.0 to 3.0, 500.0 to 0.0) + loop + listOf(470.0 to 250.0))
        r.at(470.0, 250.0)
        val jumps = r.progress.zipWithNext { a, b -> b - a }
        assertTrue(jumps.all { it <= 15.0 }, "max progress step ${jumps.maxOrNull()}")
        val lm = r.ev.filterIsInstance<NavEvent.RouteReady>().single().maneuvers
            .indexOfFirst { it.type != ManeuverType.DEPART && it.type != ManeuverType.ARRIVE }
        assertTrue(lm > 0)
        assertTrue(r.ev.filterIsInstance<NavEvent.Prompt>().any { it.maneuver == lm && it.stage == PromptStage.NOW })
        assertEquals(1, r.ev.count { it is NavEvent.Arrived })
    }

    @Test fun jumpAheadAfterOutageGivesNoStalePrompts() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val r = Run(f)
        r.path(listOf(0.0 to 0.0, 650.0 to 0.0))
        val turn = f.maneuvers.indexOfFirst { it.type == ManeuverType.RIGHT }
        val tJump = r.t
        // Позиция после пропадания на 350 м дальше по маршруту, поворот уже позади.
        r.path(listOf(1000.0 to -150.0, 1000.0 to -300.0))
        assertTrue(r.ev.filterIsInstance<NavEvent.Prompt>().none { it.maneuver == turn && it.tMs >= tJump })
        assertEquals(1, r.ev.count { it is NavEvent.RouteReady })
    }

    @Test fun nanSigmaUsesBaseThreshold() {
        val ok = drive(RouteFollower(graph(), 1000.0, -500.0), listOf(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to -500.0), sigma = Double.NaN)
        assertEquals(1, ok.count { it is NavEvent.RouteReady }); assertEquals(1, ok.count { it is NavEvent.Arrived })
        // 55 м в стороне при σ = NaN: порог 40 м — съезд распознан.
        val off = drive(RouteFollower(graph(), 1000.0, -500.0), listOf(0.0 to 0.0, 300.0 to 0.0, 300.0 to 55.0, 900.0 to 55.0),
            sigma = Double.NaN)
        val rr = off.filterIsInstance<NavEvent.RouteReady>()
        assertTrue(rr.size >= 2, "routes: ${rr.size}"); assertTrue(rr[1].reroute)
    }

    @Test fun arrivalWhenStoppedShortOfDestination() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val r = Run(f)
        r.path(listOf(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to -460.0))
        r.at(1000.0, -460.0)
        assertEquals(0, r.ev.count { it is NavEvent.Arrived })      // в движении 40 м до цели — ещё не прибыли
        r.park(1000.0, -460.0, 3)
        assertEquals(1, r.ev.count { it is NavEvent.Arrived })
        assertTrue(f.arrived)
    }

    @Test fun wrongDirectionStartReplannedOnceWhenHeadingAppears() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val r = Run(f)
        r.at(1300.0, 0.0, v = 0.0)                 // стоим: курса нет, кратчайший путь — на запад
        val first = r.ev.filterIsInstance<NavEvent.RouteReady>().single()
        assertTrue(first.route.points[1][0] < 1300.0)
        r.path(listOf(1300.0 to 0.0, 1600.0 to 0.0, 1600.0 to -300.0))   // едем на восток
        val rr = r.ev.filterIsInstance<NavEvent.RouteReady>()
        assertEquals(2, rr.size); assertTrue(rr.none { it.reroute })
        assertTrue(rr[1].route.points[1][0] > rr[1].route.points[0][0])
    }

    /** Направо на (1000,0), через 80 м налево на (1000,−80) на восток до (1400,−80). */
    private fun closeTurnsGraph(): RoadIndex {
        val nodes = ArrayList<Pair<Double, Double>>()
        fun add(e: Double, n: Double): Int { nodes += e to n; return nodes.size - 1 }
        val edges = ArrayList<EdgeSpec>()
        fun chain(ids: List<Int>, way: Long) { for (i in 0 until ids.size - 1) edges += EdgeSpec(ids[i], ids[i + 1], way) }
        val west = (0..10).map { add(100.0 * it, 0.0) }
        chain(listOf(west.last()) + (11..13).map { add(100.0 * it, 0.0) }, 3)
        val south = listOf(west.last()) + listOf(-80.0, -200.0, -300.0).map { add(1000.0, it) }
        chain(west, 1); chain(south, 2)
        chain(listOf(south[1]) + (11..14).map { add(100.0 * it, -80.0) }, 4)
        return RoadIndex(roadPackOf(enu, nodes, edges), enu)
    }

    @Test fun closeManeuversChainedWithThen() {
        val f = RouteFollower(closeTurnsGraph(), 1300.0, -80.0)
        val ev = drive(f, listOf(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to -80.0, 1300.0 to -80.0))
        val m = ev.filterIsInstance<NavEvent.RouteReady>().single().maneuvers
        val right = m.indexOfFirst { it.type == ManeuverType.RIGHT }
        val left = m.indexOfFirst { it.type == ManeuverType.LEFT }
        assertEquals(right + 1, left)
        val prompts = ev.filterIsInstance<NavEvent.Prompt>()
        val pr = prompts.filter { it.maneuver == right }
        assertEquals(listOf(PromptStage.FAR, PromptStage.NEAR, PromptStage.NOW), pr.map { it.stage })
        assertEquals(null, pr[0].thenManeuver)
        assertEquals("Через 100 м поверните направо, затем поверните налево", pr[1].text)
        assertEquals(left, pr[1].thenManeuver)
        assertEquals("Поверните направо, затем поверните налево", pr[2].text)
        assertEquals(left, pr[2].thenManeuver)
        // У второго манёвра «далеко» и «близко» уже покрыты цепочкой — звучит только «сейчас».
        assertEquals(listOf(PromptStage.NOW), prompts.filter { it.maneuver == left }.map { it.stage })
        assertTrue(NavFormat.event(pr[1], enu).contains("\"maneuver\":$right,\"then\":$left,\"stage\":\"near\""))
        assertEquals(1, ev.count { it is NavEvent.Arrived })
    }
}
