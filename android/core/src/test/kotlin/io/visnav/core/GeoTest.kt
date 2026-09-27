package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {
    @Test fun oneDegreeLatitude() =
        assertEquals(111_195.0, Geo.haversineM(0.0, 0.0, 1.0, 0.0), 111.0)

    // Эталоны посчитаны vpr_bench.geo.haversine_m — формулы должны совпадать.
    @Test fun matchesPythonImplementation() {
        assertEquals(1275.9191159603824, Geo.haversineM(55.75, 37.62, 55.76, 37.63), 1e-6)
        assertEquals(100.07543398040785, Geo.haversineM(55.75, 37.6, 55.7509, 37.6), 1e-6)
    }

    @Test fun zeroDistance() = assertEquals(0.0, Geo.haversineM(55.75, 37.6, 55.75, 37.6), 0.0)
}
