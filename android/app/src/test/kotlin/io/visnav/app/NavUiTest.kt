package io.visnav.app

import io.visnav.core.Enu
import io.visnav.core.Maneuver
import io.visnav.core.ManeuverType
import io.visnav.core.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NavUiTest {
    private val enu = Enu(55.75, 37.60)
    // 0 → 300 м на север, затем 400 м на восток: длина 700 м.
    private val route = Route(
        steps = emptyList(),
        points = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 300.0), doubleArrayOf(400.0, 300.0)),
        cumM = doubleArrayOf(0.0, 300.0, 700.0),
        durationS = 140.0,
    )
    private val maneuvers = listOf(
        Maneuver(ManeuverType.DEPART, 0.0, 0.0, 0.0, "Старая"),
        Maneuver(ManeuverType.ROUNDABOUT, 300.0, 0.0, 300.0, "Тверская улица", exit = 2),
        Maneuver(ManeuverType.ARRIVE, 700.0, 400.0, 300.0, null),
    )

    private fun ui(sendRoute: Boolean, progressM: Double = 100.0, version: Int = 3) = navUiOf(
        route = route, maneuvers = maneuvers, next = 1, distanceToNextM = 300.0 - progressM, progressM = progressM,
        arrived = false, rerouted = false, routeFailed = false, routeVersion = version, sendRoute = sendRoute, enu = enu,
    )

    @Test fun nextManeuverAndRemaining() {
        val u = ui(sendRoute = false)
        assertEquals(ManeuverType.ROUNDABOUT, u.nextType)
        assertEquals(2, u.nextExit)
        assertEquals("Тверская улица", u.nextStreet)
        assertEquals(200.0, u.nextDistM!!, 1e-9)
        assertEquals(600.0, u.remainingM!!, 1e-9)
        assertEquals(0.7, u.routeKm!!, 1e-9)
        assertEquals(140.0 / 60, u.routeMin!!, 1e-9)
        assertEquals(3, u.routeVersion)
        assertTrue(u.nextText!!.startsWith("Через 200 м"), u.nextText)
    }

    @Test fun routeGeometryOnlyWhenVersionChanged() {
        val quiet = ui(sendRoute = false)
        assertTrue(quiet.routeLatLon.isEmpty() && quiet.maneuverLatLon.isEmpty())
        val sent = ui(sendRoute = true)
        assertEquals(3, sent.routeLatLon.size)
        val end = enu.toLatLon(400.0, 300.0)
        assertEquals(end[0], sent.routeLatLon[2][0], 1e-12)
        assertEquals(end[1], sent.routeLatLon[2][1], 1e-12)
        // Манёвры без DEPART: кольцо и финиш.
        assertEquals(2, sent.maneuverLatLon.size)
        val ring = enu.toLatLon(0.0, 300.0)
        assertEquals(ring[0], sent.maneuverLatLon[0][0], 1e-12)
        assertEquals(ring[1], sent.maneuverLatLon[0][1], 1e-12)
    }

    @Test fun remainingNeverNegative() {
        assertEquals(0.0, ui(sendRoute = false, progressM = 710.0).remainingM!!, 0.0)
    }

    @Test fun noRouteYet() {
        val u = navUiOf(
            route = null, maneuvers = emptyList(), next = null, distanceToNextM = null, progressM = 0.0,
            arrived = false, rerouted = false, routeFailed = true, routeVersion = 0, sendRoute = false, enu = enu,
        )
        assertNull(u.nextType); assertNull(u.nextDistM); assertNull(u.remainingM); assertNull(u.nextText)
        assertEquals(0, u.nextExit)
        assertTrue(u.routeFailed && u.routeLatLon.isEmpty())
    }

    @Test fun mergeKeepsRouteOfSameVersion() {
        val first = ui(sendRoute = true)
        val next = ui(sendRoute = false, progressM = 150.0)
        val merged = mergeNav(first, next)!!
        assertSame(first.routeLatLon, merged.routeLatLon)
        assertSame(first.maneuverLatLon, merged.maneuverLatLon)
        assertEquals(150.0, merged.nextDistM!!, 1e-9)
    }

    @Test fun mergeTakesNewVersionAndKeepsPreviousOnNull() {
        val first = ui(sendRoute = true)
        val newer = ui(sendRoute = true, version = 4)
        assertSame(newer, mergeNav(first, newer))
        assertSame(first, mergeNav(first, null))
        assertNull(mergeNav(null, null))
    }
}
