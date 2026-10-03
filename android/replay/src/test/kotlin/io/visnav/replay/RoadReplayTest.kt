package io.visnav.replay

import io.visnav.core.RoadPack
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class RoadReplayTest {
    /** Дорога way 1 вдоль синтетического трека (n = 0), e от −200 до 1500 м, узлы через 100 м. */
    private fun roads(): RoadPack {
        val es = (-2..15).map { it * 100.0 }
        val ll = es.map { SyntheticSession.enu.toLatLon(it, 0.0) }
        val m = es.size - 1
        return RoadPack(
            DoubleArray(es.size) { ll[it][0] }, DoubleArray(es.size) { ll[it][1] }, LongArray(m) { 1L },
            IntArray(m) { it }, IntArray(m) { it + 1 }, ByteArray(m), ByteArray(m) { 7 },
        )
    }

    private fun session(): SessionData = SyntheticSession.session(createTempDirectory().toFile())

    @Test fun replayWithRoadsWritesRoadFields() {
        val outage = Outage(SyntheticSession.T0 + 30_000, SyntheticSession.T0 + 90_000)
        val points = Replayer(SyntheticSession.pack(), ReplayConfig(visual = false), roads())
            .run(session(), listOf(outage))
        val inOut = points.filter { it.inOutage && it.tMs >= SyntheticSession.T0 + 33_000 }
        assertTrue(inOut.count { it.road?.wayId == 1L } >= inOut.size * 98 / 100)
        assertTrue(inOut.count { it.road?.used == true } >= inOut.size * 80 / 100)
        assertTrue(points.filter { it.tMs < SyntheticSession.T0 + 30_000 }.none { it.road?.used == true })
    }

    @Test fun headerAndRowsCarryRoads() {
        val s = session()
        val config = ReplayConfig(visual = false, roadConstraint = false)
        val points = Replayer(SyntheticSession.pack(), config, roads()).run(s, emptyList())
        val f = File(createTempDirectory().toFile(), "t.jsonl")
        TrajectoryWriter.write(f, s, config, emptyList(), points, roadsCreatedAt = "r1")
        val lines = f.readLines()
        assertTrue(lines[0].contains("\"spoofs\":[],\"roads\":\"r1\",\"road_constraint\":false,"), lines[0])
        assertTrue(lines[1].contains("\"way_id\":1,"), lines[1])
    }
}
