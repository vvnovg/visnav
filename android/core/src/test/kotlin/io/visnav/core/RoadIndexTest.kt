package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoadIndexTest {
    private val enu = Enu(55.75, 37.60)

    @Test fun projectClampsToSegmentEnds() {
        val idx = RoadIndex(roadPackOf(enu, listOf(0.0 to 0.0, 100.0 to 0.0), listOf(EdgeSpec(0, 1, 1))), enu)
        val mid = idx.project(0, 50.0, 10.0)
        assertEquals(0.5, mid.t, 1e-6); assertEquals(10.0, mid.distM, 1e-6)
        assertEquals(0.0, idx.project(0, -20.0, 0.0).t); assertEquals(20.0, idx.project(0, -20.0, 0.0).distM, 1e-6)
        assertEquals(1.0, idx.project(0, 130.0, 0.0).t)
        assertEquals(Math.PI / 2, idx.bearing[0], 1e-6)  // на восток
    }

    @Test fun nearFindsLongEdgesAcrossCellsSortedByDistance() {
        val pack = roadPackOf(enu, listOf(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 30.0, 1000.0 to 30.0),
            listOf(EdgeSpec(0, 1, 1), EdgeSpec(2, 3, 2)))
        val idx = RoadIndex(pack, enu)
        val hits = idx.near(500.0, 5.0, 50.0)
        assertEquals(listOf(0, 1), hits.map { it.edge })
        assertEquals(5.0, hits[0].distM, 1e-3); assertEquals(25.0, hits[1].distM, 1e-3)
        assertTrue(idx.near(500.0, 100.0, 50.0).isEmpty())
    }

    @Test fun degreeAndOnewayAdjacency() {
        val pack = roadPackOf(enu, listOf(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0),
            listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1, flags = RoadPack.FLAG_ONEWAY)))
        val idx = RoadIndex(pack, enu)
        assertEquals(listOf(1, 2, 1), idx.degree.toList())
        assertTrue(1 in idx.outNodes[0].toList() && 2 in idx.outNodes[1].toList() && 0 in idx.outNodes[1].toList())
        assertFalse(1 in idx.outNodes[2].toList())  // по односторонней назад нельзя
    }

    @Test fun routeFromRespectsOnewayAndLimit() {
        val pack = roadPackOf(enu, listOf(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0),
            listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1, flags = RoadPack.FLAG_ONEWAY)))
        val idx = RoadIndex(pack, enu)
        assertEquals(200.0, idx.routeFrom(0, 500.0)[2]!!, 1e-3)
        assertFalse(0 in idx.routeFrom(2, 500.0))
        val limited = idx.routeFrom(0, 150.0)
        assertTrue(1 in limited); assertFalse(2 in limited)
    }
}
