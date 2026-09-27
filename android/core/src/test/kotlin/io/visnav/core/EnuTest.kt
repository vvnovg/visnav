package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class EnuTest {
    @Test fun northAndEastMetersMatchHaversine() {
        val enu = Enu(55.75, 37.6)
        val north = enu.toLatLon(0.0, 100.0)
        assertEquals(100.0, Geo.haversineM(55.75, 37.6, north[0], north[1]), 0.01)
        val east = enu.toLatLon(100.0, 0.0)
        assertEquals(100.0, Geo.haversineM(55.75, 37.6, east[0], east[1]), 0.05)
    }

    @Test fun roundTrip() {
        val enu = Enu(55.75, 37.6)
        val en = enu.toEn(55.7612, 37.6231)
        val back = enu.toLatLon(en[0], en[1])
        assertEquals(55.7612, back[0], 1e-9); assertEquals(37.6231, back[1], 1e-9)
    }
}
