package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test fun firstPromptOfRerouteComesWithRoute() {
        // W(0,0)–J(1000,0): в J направо на юг к цели (1000,−500); прямо — K(1150,0) с отростком на восток и улицей
        // на юг к (1150,−300), оттуда на запад к M(1000,−300) на южной улице. Поворот в J пропущен: перестроение
        // в (1105, 0) (съезд с 1045 м + 4 с), до поворота в K 45 м — в пределах «близко» (120 м при 15 м/с), но дальше
        // «сейчас» (37,5 м).
        val nodes = listOf(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to -300.0, 1000.0 to -600.0, 1150.0 to 0.0,
            1150.0 to -300.0, 1300.0 to 0.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 2), EdgeSpec(1, 4, 3), EdgeSpec(4, 5, 4),
            EdgeSpec(5, 2, 5), EdgeSpec(4, 6, 3))
        val f = RouteFollower(RoadIndex(roadPackOf(enu, nodes, edges), enu), 1000.0, -500.0)
        val ev = drive(f, listOf(0.0 to 0.0, 1000.0 to 0.0, 1300.0 to 0.0))
        val rr = ev.filterIsInstance<NavEvent.RouteReady>().first { it.reroute }
        val k = rr.maneuvers.indexOfFirst { it.type == ManeuverType.RIGHT }
        assertEquals(1150.0, rr.maneuvers[k].e, 1e-6)
        val at = ev.indexOf(rr)
        val near = ev[at + 1] as NavEvent.Prompt
        assertEquals(rr.tMs, near.tMs)
        assertEquals(k, near.maneuver); assertEquals(PromptStage.NEAR, near.stage)
        assertEquals(45.0, near.distM, 1e-6)
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
        assertEquals("Через 100 м поверните направо, затем налево", pr[1].text)
        assertEquals(left, pr[1].thenManeuver)
        assertEquals("Поверните направо, затем налево", pr[2].text)
        assertEquals(left, pr[2].thenManeuver)
        // У второго манёвра «далеко» и «близко» уже покрыты цепочкой — звучит только «сейчас».
        assertEquals(listOf(PromptStage.NOW), prompts.filter { it.maneuver == left }.map { it.stage })
        assertTrue(NavFormat.event(pr[1], enu).contains("\"maneuver\":$right,\"then\":$left,\"stage\":\"near\""))
        assertEquals(1, ev.count { it is NavEvent.Arrived })
    }

    /**
     * Разделённая дорога: на восток по n=0 (одностороннее) до (500,0), полукруглый разворот (одностороннее)
     * на линию n=−gap, по ней на запад (одностороннее) до (−200,−gap); от (100,−gap) на юг двусторонняя до (100,−200).
     */
    private fun dividedRoad(gap: Double): Pair<RoadIndex, List<Pair<Double, Double>>> {
        val nodes = ArrayList<Pair<Double, Double>>()
        fun add(e: Double, n: Double): Int { nodes += e to n; return nodes.size - 1 }
        val edges = ArrayList<EdgeSpec>()
        fun chain(ids: List<Int>, way: Long, flags: Int = 0) {
            for (i in 0 until ids.size - 1) edges += EdgeSpec(ids[i], ids[i + 1], way, flags)
        }
        val ow = RoadPack.FLAG_ONEWAY
        val east = (0..5).map { add(100.0 * it, 0.0) }
        val link = (1..5).map { k ->
            val th = Math.toRadians(90.0 - 30.0 * k)
            500.0 + gap / 2 * kotlin.math.cos(th) to -gap / 2 + gap / 2 * kotlin.math.sin(th)
        }
        val linkIds = link.map { add(it.first, it.second) }
        val west = (5 downTo -2).map { add(100.0 * it, -gap) }
        val south = listOf(west[4]) + listOf(-100.0, -200.0).map { add(100.0, it) }
        chain(east, 1, ow); chain(listOf(east.last()) + linkIds + west.first(), 2, ow); chain(west, 3, ow); chain(south, 4)
        return RoadIndex(roadPackOf(enu, nodes, edges), enu) to (listOf(50.0 to 0.0, 500.0 to 0.0) + link +
            listOf(500.0 to -gap, 100.0 to -gap, 100.0 to -150.0))
    }

    @Test fun narrowDividedRoadUTurnKeepsProgress() {
        for (gap in listOf(6.0, 8.0)) {
            val (g, path) = dividedRoad(gap)
            val f = RouteFollower(g, 100.0, -150.0)
            val r = Run(f)
            val trueArc = ArrayList<Double>()
            var arc = 0.0
            for (k in 1 until path.size) {
                val (e0, n0) = path[k - 1]; val (e1, n1) = path[k]
                val len = kotlin.math.hypot(e1 - e0, n1 - n0)
                val psi = kotlin.math.atan2(e1 - e0, n1 - n0)
                var s = 0.0
                while (s < len) {
                    r.at(e0 + (e1 - e0) * s / len, n0 + (n1 - n0) * s / len, psi = psi)
                    trueArc += arc + s; s += 7.5
                }
                arc += len
            }
            // Путь до конца разворота (точка 7 — (500, −gap)); обратная ветка — следующие 400 м до (100, −gap).
            // Южный участок не берём: после прибытия (за 25 м до конца) прогресс больше не обновляется.
            val uturnEnd = path.zipWithNext().take(7).sumOf { (a, b) -> kotlin.math.hypot(b.first - a.first, b.second - a.second) }
            val lag = trueArc.indices.filter { trueArc[it] >= uturnEnd && trueArc[it] <= uturnEnd + 400.0 }
                .maxOf { trueArc[it] - r.progress[it] }
            assertTrue(lag <= 20.0, "gap $gap: progress lags by $lag m on the return leg")
            val m = r.ev.filterIsInstance<NavEvent.RouteReady>().single().maneuvers
            val left = m.indexOfFirst { it.type == ManeuverType.LEFT }
            assertTrue(left > 0, "gap $gap: maneuvers ${m.map { it.type }}")
            assertTrue(r.ev.filterIsInstance<NavEvent.Prompt>().any { it.maneuver == left && it.stage == PromptStage.NOW },
                "gap $gap: no NOW for the return-leg turn")
        }
    }

    @Test fun passingNearDestEarlyDoesNotArrive() {
        // На восток по n=0 до (600,0), на север до (600,20), на запад по n=20; цель (460,20). Маршрут проходит
        // в 20 м от цели у (460,0), когда до конца ещё 300 м.
        val nodes = ArrayList<Pair<Double, Double>>()
        fun add(e: Double, n: Double): Int { nodes += e to n; return nodes.size - 1 }
        val edges = ArrayList<EdgeSpec>()
        fun chain(ids: List<Int>, way: Long) { for (i in 0 until ids.size - 1) edges += EdgeSpec(ids[i], ids[i + 1], way) }
        val a = (0..6).map { add(100.0 * it, 0.0) }
        val c = listOf(a.last()) + listOf(add(600.0, 20.0)) + (5 downTo 1).map { add(100.0 * it, 20.0) }
        chain(a, 1); chain(c, 2)
        val f = RouteFollower(RoadIndex(roadPackOf(enu, nodes, edges), enu), 460.0, 20.0,
            routerConfig = RouterConfig(goalSlackM = 10.0))
        val r = Run(f)
        r.path(listOf(0.0 to 0.0, 600.0 to 0.0))
        assertEquals(1, r.ev.count { it is NavEvent.RouteReady })
        assertEquals(760.0, f.route!!.lengthM, 1.0)        // 600 + 20 + 140: у (460,0) до конца 300 м
        assertEquals(0, r.ev.count { it is NavEvent.Arrived })
        r.path(listOf(600.0 to 0.0, 600.0 to 20.0, 460.0 to 20.0))
        r.at(460.0, 20.0)
        assertEquals(1, r.ev.count { it is NavEvent.Arrived })
    }

    @Test fun longUpdateGapGivesNoStalePrompt() {
        // Узлы через 10 м, чтобы прежнее окно (200 м) останавливало прогресс у самого поворота.
        val (wn, we) = straightRoad(0.0, 0.0, 1000.0, 0.0, 10.0, 1, 0)
        val (sn, se) = straightRoad(1000.0, 0.0, 1000.0, -500.0, 10.0, 2, wn.size, startNode = wn.size - 1)
        val (en, ee) = straightRoad(1000.0, 0.0, 1300.0, 0.0, 10.0, 3, wn.size + sn.size, startNode = wn.size - 1)
        val g = RoadIndex(roadPackOf(enu, wn + sn + en, we + se + ee), enu)
        val f = RouteFollower(g, 1000.0, -400.0)
        val r = Run(f)
        r.path(listOf(0.0 to 0.0, 795.0 to 0.0))            // последняя точка — 787,5 м
        val turn = f.maneuvers.indexOfFirst { it.type == ManeuverType.RIGHT }
        assertTrue(turn > 0)
        r.t += 14_500                                         // 15 с без обновлений: 225 м при 15 м/с
        val tGap = r.t
        r.path(listOf(1000.0 to -12.5, 1000.0 to -200.0))
        assertTrue(r.ev.filterIsInstance<NavEvent.Prompt>().none { it.maneuver == turn && it.tMs >= tGap })
        assertEquals(1, r.ev.count { it is NavEvent.RouteReady })
    }

    /**
     * Разворот на узкой разделённой дороге с манёвром: на восток по n=0 (одностороннее, дальше до (700,0)), у (500,0)
     * перемычка на юг до (500,−sep) (одностороннее), по n=−sep на запад (одностороннее, от (700,−sep) до (−200,−sep));
     * от (100,−sep) на юг двусторонняя до (100,−200). Оба узла перемычки — перекрёстки, их повороты сливаются в UTURN.
     */
    private fun uturnRoad(sep: Double): RoadIndex {
        val nodes = ArrayList<Pair<Double, Double>>()
        fun add(e: Double, n: Double): Int { nodes += e to n; return nodes.size - 1 }
        val edges = ArrayList<EdgeSpec>()
        fun chain(ids: List<Int>, way: Long, flags: Int = 0) {
            for (i in 0 until ids.size - 1) edges += EdgeSpec(ids[i], ids[i + 1], way, flags)
        }
        val ow = RoadPack.FLAG_ONEWAY
        val east = (0..7).map { add(100.0 * it, 0.0) }
        val west = (7 downTo -2).map { add(100.0 * it, -sep) }
        chain(east, 1, ow); chain(listOf(east[5], west[2]), 2, ow); chain(west, 3, ow)
        chain(listOf(west[6]) + listOf(-100.0, -200.0).map { add(100.0, it) }, 4)
        return RoadIndex(roadPackOf(enu, nodes, edges), enu)
    }

    @Test fun standstillJitterBeforeNarrowUTurnKeepsProgress() {
        val sep = 8.0
        val f = RouteFollower(uturnRoad(sep), 100.0, -150.0)
        val r = Run(f)
        r.path(listOf(0.0 to 0.0, 450.0 to 0.0))
        r.at(450.0, 0.0, psi = kotlin.math.PI / 2)
        val uturn = f.maneuvers.indexOfFirst { it.type == ManeuverType.UTURN }
        assertTrue(uturn > 0, "maneuvers ${f.maneuvers.map { it.type }}")
        // Стоим 30 с в 50 м до разворота; каждое 4-е обновление позиция на 5 м позади (шум вдоль дороги).
        val stopStart = r.progress.size
        for (k in 0 until 60) r.at(if (k % 4 == 3) 445.0 else 450.0, 0.0, psi = kotlin.math.PI / 2, v = 0.0)
        val stopped = r.progress.subList(stopStart - 1, r.progress.size)
        val maxJump = stopped.zipWithNext { a, b -> b - a }.maxOrNull()!!
        assertTrue(maxJump <= 30.0, "progress jumped $maxJump m while stopped")
        r.path(listOf(450.0 to 0.0, 500.0 to 0.0, 500.0 to -sep, 100.0 to -sep, 100.0 to -150.0))
        assertTrue(r.ev.filterIsInstance<NavEvent.Prompt>().any { it.maneuver == uturn && it.stage == PromptStage.NOW },
            "prompts ${r.ev.filterIsInstance<NavEvent.Prompt>().map { it.maneuver to it.stage }}")
    }

    /** Улица W(0,0)–A(100,0)–B(200,0) и отросток на юг до S(200,−50); S — край коридора (разворота там нет). */
    private fun corridorEdge(): RoadIndex {
        val nodes = listOf(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 200.0 to -50.0)
        val base = roadPackOf(enu, nodes, listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 3)))
        val p = RoadPack(base.lats, base.lons, base.way, base.from, base.to, base.flags, base.cls, boundary = intArrayOf(3))
        return RoadIndex(p, enu)
    }

    private fun assertUturnFirst(ev: List<NavEvent>, reroute: Boolean) {
        val ready = ev.filterIsInstance<NavEvent.RouteReady>().single()
        assertEquals(reroute, ready.reroute)
        assertTrue(ready.route.startsAgainstHeading)
        val prompt = ev.filterIsInstance<NavEvent.Prompt>().single()
        assertEquals(ManeuverType.UTURN, ready.maneuvers[prompt.maneuver].type)
        assertEquals(PromptStage.NOW, prompt.stage)
        assertEquals("Развернитесь", prompt.text)
    }

    @Test fun startAgainstHeadingPromptsUturnWithRoute() {
        // Курс на восток к краю коридора, цель позади: маршрут только против курса, первая подсказка — «Развернитесь».
        val f = RouteFollower(corridorEdge(), 20.0, 0.0)
        assertUturnFirst(f.update(0, 150.0, 0.0, 5.0, Math.PI / 2, 15.0), reroute = false)
    }

    @Test fun rerouteAgainstHeadingPromptsUturn() {
        // Маршрут на запад; машина уезжает на восток (30 м от начала маршрута > порога 20 м) — перестроение против курса
        // (после rerouteCooldownMs от первого плана, t = 10 с).
        val f = RouteFollower(corridorEdge(), 20.0, 0.0, NavConfig(offRouteM = 20.0))
        val first = f.update(0, 150.0, 0.0, 5.0, -Math.PI / 2, 15.0)
        assertTrue(!first.filterIsInstance<NavEvent.RouteReady>().single().route.startsAgainstHeading)
        f.update(500, 145.0, 0.0, 5.0, -Math.PI / 2, 15.0)   // на маршруте: съезд дальше ждёт offRouteHoldMs
        var t = 1000L
        var ev: List<NavEvent> = emptyList()
        while (t <= 10_000L && ev.none { it is NavEvent.RouteReady }) { ev = f.update(t, 180.0, 0.0, 5.0, Math.PI / 2, 15.0); t += 500 }
        assertUturnFirst(ev, reroute = true)
    }

    /** corridorEdge и боковая улица на юг из A(100,0) до (100,−100): с запада на юг — налево. */
    private fun corridorEdgeWithSide(): RoadIndex {
        val nodes = listOf(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 200.0 to -50.0, 100.0 to -100.0)
        val base = roadPackOf(enu, nodes,
            listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 3), EdgeSpec(1, 4, 4)))
        val p = RoadPack(base.lats, base.lons, base.way, base.from, base.to, base.flags, base.cls, boundary = intArrayOf(3))
        return RoadIndex(p, enu)
    }

    @Test fun startUturnNowSpokenOnceWhileMovingAway() {
        val f = RouteFollower(corridorEdge(), 20.0, 0.0)
        val ev = ArrayList<NavEvent>()
        ev += f.update(0, 150.0, 0.0, 5.0, Math.PI / 2, 15.0)
        for (k in 1..10) ev += f.update(500L * k, 150.0 + 2.0 * k, 0.0, 5.0, Math.PI / 2, 15.0)
        assertEquals(0.0, f.progressM)
        val prompts = ev.filterIsInstance<NavEvent.Prompt>()
        assertEquals(listOf(1 to PromptStage.NOW), prompts.map { it.maneuver to it.stage })
        assertEquals(1, ev.count { it is NavEvent.RouteReady })
    }

    @Test fun afterStartUturnNextIsRealManeuver() {
        val f = RouteFollower(corridorEdgeWithSide(), 100.0, -80.0)
        f.update(0, 150.0, 0.0, 5.0, Math.PI / 2, 15.0)
        assertEquals(1, f.nextManeuver)
        assertEquals(ManeuverType.UTURN, f.maneuvers[1].type)
        f.update(500, 145.0, 0.0, 5.0, -Math.PI / 2, 15.0)   // развернулись, 5 м по маршруту
        assertTrue(f.progressM > 1.0)
        val next = assertNotNull(f.nextManeuver)
        assertEquals(ManeuverType.LEFT, f.maneuvers[next].type)
        assertEquals(45.0, assertNotNull(f.distanceToNextM), 1e-6)
    }

    @Test fun startUturnChainedWithNearTurn() {
        // Поворот налево через 50 м после старта — в пределах «близко» (120 м при 15 м/с): «Развернитесь, затем налево».
        val f = RouteFollower(corridorEdgeWithSide(), 100.0, -80.0)
        val ev = f.update(0, 150.0, 0.0, 5.0, Math.PI / 2, 15.0)
        val p = ev.filterIsInstance<NavEvent.Prompt>().single()
        assertEquals(PromptStage.NOW, p.stage)
        assertEquals("Развернитесь, затем налево", p.text)
        assertEquals(ManeuverType.LEFT, f.maneuvers[assertNotNull(p.thenManeuver)].type)
    }
}
