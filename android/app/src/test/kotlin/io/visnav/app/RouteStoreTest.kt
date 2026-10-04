package io.visnav.app

import io.visnav.core.MapGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RouteStoreTest {
    private val line = listOf(doubleArrayOf(55.75, 37.60), doubleArrayOf(55.76, 37.61))
    private val other = listOf(doubleArrayOf(55.70, 37.50), doubleArrayOf(55.71, 37.52))
    private val turns = listOf(doubleArrayOf(55.76, 37.61))

    private fun nav(version: Int, route: List<DoubleArray> = emptyList(), maneuvers: List<DoubleArray> = emptyList()) =
        NavUi(nextText = null, routeKm = null, routeMin = null, arrived = false, rerouted = false, routeFailed = false,
            routeVersion = version, routeLatLon = route, maneuverLatLon = maneuvers)

    @Test fun newVersionReplacesRoute() {
        val store = RouteStore()
        store.update(nav(1, line, turns))
        assertEquals(1, store.version)
        assertEquals(MapGeometry.lineString(line), store.routeJson)
        assertEquals(MapGeometry.points(turns), store.maneuversJson)
        store.update(nav(2, other))
        assertEquals(2, store.version)
        assertEquals(MapGeometry.lineString(other), store.routeJson)
        assertEquals(MapGeometry.points(emptyList()), store.maneuversJson)
    }

    @Test fun sameVersionWithEmptyListsKeepsRoute() {
        val store = RouteStore()
        store.update(nav(5, line, turns))
        store.update(nav(5))
        assertEquals(5, store.version)
        assertEquals(MapGeometry.lineString(line), store.routeJson)
        assertEquals(MapGeometry.points(turns), store.maneuversJson)
    }

    @Test fun nullNavOrVersionZeroClears() {
        val store = RouteStore()
        store.update(nav(3, line, turns))
        store.update(null)
        assertEquals(0, store.version)
        assertEquals(MapGeometry.empty(), store.routeJson)
        assertEquals(MapGeometry.empty(), store.maneuversJson)

        store.update(nav(4, line, turns))
        assertNotEquals(MapGeometry.empty(), store.routeJson)
        store.update(nav(0))
        assertEquals(0, store.version)
        assertEquals(MapGeometry.empty(), store.routeJson)
        assertEquals(MapGeometry.empty(), store.maneuversJson)
    }
}
