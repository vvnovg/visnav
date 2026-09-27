package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeoIndexTest {
    private val one: Short = 0x3C00
    // Три эталона через ~100 м к северу, дескрипторы — единичные векторы.
    private val pack = RefPack(
        count = 3, dim = 3,
        lats = doubleArrayOf(55.7500, 55.7509, 55.7518),
        lons = doubleArrayOf(37.6, 37.6, 37.6),
        headings = floatArrayOf(0f, 0f, 0f),
        descriptors = shortArrayOf(one, 0, 0, 0, one, 0, 0, 0, one),
    )
    private val index = GeoIndex(pack)

    @Test fun ordersBySimilarity() {
        val hits = index.search(floatArrayOf(0f, 0.6f, 0.8f), k = 2)
        assertEquals(listOf(2, 1), hits.map { it.index })
        assertEquals(0.8f, hits[0].sim, 1e-6f)
    }

    @Test fun geoWindowExcludesFarCandidates() {
        val hits = index.search(floatArrayOf(0f, 0f, 1f), k = 3, centerLat = 55.75, centerLon = 37.6, radiusM = 50.0)
        assertEquals(listOf(0), hits.map { it.index })
    }

    @Test fun emptyWhenNothingInWindow() {
        assertTrue(index.search(floatArrayOf(1f, 0f, 0f), k = 3, centerLat = 0.0, centerLon = 0.0, radiusM = 10.0).isEmpty())
    }

    @Test fun kLargerThanCountReturnsAll() {
        assertEquals(3, index.search(floatArrayOf(1f, 1f, 1f), k = 10).size)
    }

    @Test fun rejectsWrongDimensionAndK() {
        assertFailsWith<IllegalArgumentException> { index.search(floatArrayOf(1f, 0f), k = 1) }
        assertFailsWith<IllegalArgumentException> { index.search(floatArrayOf(1f, 0f, 0f), k = 0) }
    }
}
