package io.visnav.replay

import io.visnav.core.NavEvent
import io.visnav.core.RoadPack
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavReplayTest {
    /** Синтетическая сессия едет на восток по n = 0 от 0 до 1200 м; дорога way 1 вдоль неё от −200 до 1500 м. */
    private fun roads(): RoadPack {
        val es = (-2..15).map { it * 100.0 }
        val ll = es.map { SyntheticSession.enu.toLatLon(it, 0.0) }
        val m = es.size - 1
        return RoadPack(DoubleArray(es.size) { ll[it][0] }, DoubleArray(es.size) { ll[it][1] }, LongArray(m) { 1L },
            IntArray(m) { it }, IntArray(m) { it + 1 }, ByteArray(m), ByteArray(m) { 7 })
    }

    @Test fun replayNavigatesToDestinationThroughOutage() {
        val s = SyntheticSession.session(createTempDirectory().toFile())
        val outage = Outage(SyntheticSession.T0 + 30_000, SyntheticSession.T0 + 90_000)
        val points = Replayer(SyntheticSession.pack(), ReplayConfig(visual = false), roads()).run(s, listOf(outage))
        val dest = SyntheticSession.enu.toLatLon(1100.0, 0.0)
        val (_, events) = runNavigation(points, roads(), dest[0], dest[1])
        assertEquals(1, events.count { it is NavEvent.RouteReady })
        assertEquals(0, events.count { it is NavEvent.RouteReady && it.reroute })
        assertEquals(1, events.count { it is NavEvent.Arrived })
        val arrive = events.filterIsInstance<NavEvent.Prompt>().filter { it.text.contains("пункт назначения") }
        assertTrue(arrive.isNotEmpty())
    }

    @Test fun writesHeaderAndEvents() {
        val s = SyntheticSession.session(createTempDirectory().toFile())
        val points = Replayer(SyntheticSession.pack(), ReplayConfig(visual = false), roads()).run(s, emptyList())
        val dest = SyntheticSession.enu.toLatLon(1100.0, 0.0)
        val (enu, events) = runNavigation(points, roads(), dest[0], dest[1])
        val f = File(createTempDirectory().toFile(), "n.jsonl")
        writeNav(f, "{\"type\":\"nav\"}", enu, events)
        val lines = f.readLines()
        assertEquals("{\"type\":\"nav\"}", lines[0])
        assertTrue(lines[1].contains("\"ev\":\"route\"")); assertTrue(lines.last().contains("\"ev\":\"arrive\""))
    }
}
