package io.visnav.app

import io.visnav.core.NavMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerfSettingsTest {
    @Test fun defaults() {
        val s = PerfSettings()
        assertEquals(1500L, s.reorderDelayMs)
        assertEquals("cpu", s.ort)
        assertEquals("full", s.profile)
        assertEquals(0, s.durationMin)
    }

    @Test fun parseKeepsAllowedValues() {
        for (d in listOf(300L, 500L, 800L, 1500L)) assertEquals(d, PerfSettings.reorderDelay(d))
        assertEquals("xnnpack", PerfSettings.ort("xnnpack"))
        assertEquals("cpu", PerfSettings.ort("cpu"))
        assertEquals("baseline", PerfSettings.profile("baseline"))
        assertEquals("full", PerfSettings.profile("full"))
        for (m in listOf(0, 30, 60)) assertEquals(m, PerfSettings.duration(m))
    }

    @Test fun parseFallsBackToDefaults() {
        assertEquals(1500L, PerfSettings.reorderDelay(400))
        assertEquals(1500L, PerfSettings.reorderDelay(-1))
        assertEquals("cpu", PerfSettings.ort(null))
        assertEquals("cpu", PerfSettings.ort("gpu"))
        assertEquals("full", PerfSettings.profile(null))
        assertEquals("full", PerfSettings.profile("x"))
        assertEquals(0, PerfSettings.duration(45))
    }

    @Test fun nextCyclesThroughOptions() {
        assertEquals(500L, PerfSettings.next(PerfSettings.REORDER_DELAYS_MS, 300L))
        assertEquals(300L, PerfSettings.next(PerfSettings.REORDER_DELAYS_MS, 1500L))
        assertEquals(30, PerfSettings.next(PerfSettings.DURATIONS_MIN, 0))
        assertEquals(0, PerfSettings.next(PerfSettings.DURATIONS_MIN, 60))
        assertEquals("cpu", PerfSettings.next(PerfSettings.ORTS, "xnnpack"))
        // Неизвестное значение — первый вариант.
        assertEquals("full", PerfSettings.next(PerfSettings.PROFILES, "x"))
    }

    @Test fun batteryCapacity() {
        // 3 000 000 мкА·ч при 50 % → 6000 мА·ч.
        assertEquals(6000, batteryCapacityMah(3_000_000L, 50.0))
        assertNull(batteryCapacityMah(null, 50.0))
        assertNull(batteryCapacityMah(3_000_000L, null))
        assertNull(batteryCapacityMah(3_000_000L, 0.0))
    }

    @Test fun batteryPropertyValidity() {
        assertEquals(42L, batteryProp(42L))
        assertNull(batteryProp(0L))
        assertNull(batteryProp(-5L))
        assertNull(batteryProp(Long.MIN_VALUE))
        assertNull(batteryProp(Int.MIN_VALUE.toLong()))
    }

    @Test fun batteryCurrentValidity() {
        assertEquals(-350_000L, batteryCurrent(-350_000L))
        assertEquals(350_000L, batteryCurrent(350_000L))
        assertNull(batteryCurrent(0L))
        assertNull(batteryCurrent(Long.MIN_VALUE))
        assertNull(batteryCurrent(Int.MIN_VALUE.toLong()))
    }

    @Test fun boundedMapKeepsNewest() {
        val m = boundedMap<Long, Int>(64)
        for (i in 0L until 100L) m[i] = i.toInt()
        assertEquals(64, m.size)
        assertNull(m[35L])
        assertEquals(36, m[36L])
        assertEquals(99, m[99L])
    }

    @Test fun median() {
        assertNull(p50(emptyList()))
        assertEquals(2.0, p50(listOf(3.0, 1.0, 2.0)))
        assertEquals(1.0, p50(listOf(2.0, 1.0)))
    }

    @Test fun nowcastShiftsMovingPosition() {
        // 10 м/с на восток (90°), 1 с → ≈ 10 м к востоку; σ, курс и режим сохраняются.
        val p = MapPos(55.0, 37.0, 8.0, Math.PI / 2, 10.0, NavMode.VISUAL, tMs = 1_000L)
        val n = nowcastPos(p, 2_000L)
        assertEquals(55.0, n.lat, 1e-6)
        val eastM = (n.lon - 37.0) * Math.toRadians(1.0) * 6_378_137.0 * Math.cos(Math.toRadians(55.0))
        assertEquals(10.0, eastM, 0.1)
        assertEquals(8.0, n.sigmaM)
        assertEquals(NavMode.VISUAL, n.mode)
        assertEquals(1_000L, n.tMs)
    }

    @Test fun nowcastKeepsSlowPosition() {
        val p = MapPos(55.0, 37.0, 8.0, 0.0, 0.5, NavMode.GNSS, tMs = 1_000L)
        assertTrue(nowcastPos(p, 2_000L) == p)
    }
}
