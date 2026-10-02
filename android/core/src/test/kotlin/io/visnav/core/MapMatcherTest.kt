package io.visnav.core

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapMatcherTest {
    private val enu = Enu(55.75, 37.60)

    private fun graph(vararg parts: Pair<List<Pair<Double, Double>>, List<EdgeSpec>>): RoadPack =
        roadPackOf(enu, parts.flatMap { it.first }, parts.flatMap { it.second })

    /** Две параллельные двусторонние дороги: way 1 по n = 0, way 2 по n = gapM; e от 0 до 1000, узлы через 100 м. */
    private fun parallel(gapM: Double, flagsA: Int = 0): RoadPack {
        val a = straightRoad(0.0, 0.0, 1000.0, 0.0, 100.0, 1, 0, flags = flagsA)
        val b = straightRoad(0.0, gapM, 1000.0, gapM, 100.0, 2, a.first.size)
        return graph(a, b)
    }

    @Test fun staysOnTrueRoadNextToParallelOne() {
        val m = MapMatcher(RoadIndex(parallel(25.0), enu))
        val rnd = Random(1)
        val ways = (0 until 100).map { k ->
            m.step(10.0 * k, 8.0 * rnd.nextGaussian(), 8.0, Math.PI / 2, 10.0)?.wayId
        }
        val onA = ways.drop(3).count { it == 1L }
        assertTrue(onA >= 95, "on way 1: $onA of 97")
    }

    @Test fun followsTurnAtJunction() {
        // Way 1 на восток до (500, 0); там перекрёсток: way 3 на север, way 4 прямо на восток.
        val a = straightRoad(0.0, 0.0, 500.0, 0.0, 100.0, 1, 0)
        val junction = a.first.size - 1
        val c = straightRoad(500.0, 0.0, 500.0, 500.0, 100.0, 3, a.first.size, startNode = junction)
        val d = straightRoad(500.0, 0.0, 1000.0, 0.0, 100.0, 4, a.first.size + c.first.size, startNode = junction)
        val m = MapMatcher(RoadIndex(graph(a, c, d), enu))
        val rnd = Random(2)
        // На восток до перекрёстка, затем на север; (e, n, курс).
        val path = (0..50).map { Triple(10.0 * it, 0.0, Math.PI / 2) } + (1..50).map { Triple(500.0, 10.0 * it, 0.0) }
        val results = path.map { (e, n, psi) ->
            Triple(e, n, m.step(e + 3 * rnd.nextGaussian(), n + 3 * rnd.nextGaussian(), 5.0, psi, 10.0))
        }
        assertTrue(results.filter { it.second == 0.0 && it.first <= 450.0 }.all { it.third?.wayId == 1L })
        assertTrue(results.filter { it.second >= 50.0 }.all { it.third?.wayId == 3L })
    }

    @Test fun onewayForbidsWrongDirection() {
        // Way 1 односторонняя на восток, way 2 двусторонняя в 15 м; машина едет на запад ровно посередине.
        val m = MapMatcher(RoadIndex(parallel(15.0, flagsA = RoadPack.FLAG_ONEWAY), enu))
        val res = (0 until 80).map { k -> m.step(1000.0 - 10.0 * k, 7.5, 8.0, -Math.PI / 2, 10.0) }
        val tail = res.drop(5)
        assertTrue(tail.count { it?.wayId == 2L } >= 72, "way 2: ${tail.count { it?.wayId == 2L }}")
        assertTrue(tail.all { (it?.confidence ?: 0.0) >= 0.9 })
    }

    @Test fun offRoadReturnsNullAndRecovers() {
        val m = MapMatcher(RoadIndex(parallel(500.0), enu))
        repeat(5) { assertEquals(1L, m.step(10.0 * it, 0.0, 5.0, Math.PI / 2, 10.0)?.wayId) }
        assertNull(m.step(60.0, 250.0, 5.0, Math.PI / 2, 10.0))
        assertNull(m.step(70.0, 250.0, 5.0, Math.PI / 2, 10.0))
        assertEquals(1L, m.step(80.0, 0.0, 5.0, Math.PI / 2, 10.0)?.wayId)
    }

    @Test fun confidenceLowWhenAmbiguousHighWhenAlone() {
        val ambiguous = MapMatcher(RoadIndex(parallel(10.0), enu))
        val amb = (0 until 30).map { ambiguous.step(10.0 * it, 5.0, 8.0, Math.PI / 2, 10.0)!!.confidence }
        assertTrue(amb.drop(3).all { it < 0.9 }, "ambiguous: $amb")
        val alone = MapMatcher(RoadIndex(parallel(500.0), enu))
        val single = (0 until 30).map { alone.step(10.0 * it, 2.0, 8.0, Math.PI / 2, 10.0)!!.confidence }
        assertTrue(single.drop(3).all { it > 0.95 }, "alone: $single")
    }

    @Test fun nearJunctionFlag() {
        val a = straightRoad(0.0, 0.0, 500.0, 0.0, 100.0, 1, 0)
        val junction = a.first.size - 1
        val c = straightRoad(500.0, 0.0, 500.0, 500.0, 100.0, 3, a.first.size, startNode = junction)
        val d = straightRoad(500.0, 0.0, 1000.0, 0.0, 100.0, 4, a.first.size + c.first.size, startNode = junction)
        val m = MapMatcher(RoadIndex(graph(a, c, d), enu))
        val mid = m.step(250.0, 0.0, 5.0, Math.PI / 2, 10.0)
        assertNotNull(mid); assertTrue(!mid.nearJunction)
        m.reset()
        val atJunction = m.step(490.0, 0.0, 5.0, Math.PI / 2, 10.0)
        assertNotNull(atJunction); assertTrue(atJunction.nearJunction)
    }
}
