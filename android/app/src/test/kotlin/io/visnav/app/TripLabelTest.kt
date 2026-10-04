package io.visnav.app

import kotlin.test.Test
import kotlin.test.assertEquals

class TripLabelTest {
    @Test fun legacyLayoutShowsNothing() {
        assertEquals(TripLabel.None, tripLabel(null, emptyList(), running = false, loading = false))
    }

    @Test fun emptyTripsIsText() {
        assertEquals(TripLabel.Text("a"), tripLabel("a", emptyList(), running = false, loading = false))
    }

    @Test fun singleTripIsTextInAnyState() {
        assertEquals(TripLabel.Text("a"), tripLabel("a", listOf("a"), running = false, loading = false))
        assertEquals(TripLabel.Text("a"), tripLabel("a", listOf("a"), running = true, loading = false))
        assertEquals(TripLabel.Text("a"), tripLabel("a", listOf("a"), running = false, loading = true))
    }

    @Test fun severalTripsIdleIsPicker() {
        assertEquals(TripLabel.Picker("b", listOf("a", "b")), tripLabel("b", listOf("a", "b"), running = false, loading = false))
    }

    @Test fun severalTripsWhileRunningOrLoadingIsText() {
        assertEquals(TripLabel.Text("b"), tripLabel("b", listOf("a", "b"), running = true, loading = false))
        assertEquals(TripLabel.Text("b"), tripLabel("b", listOf("a", "b"), running = false, loading = true))
    }

    @Test fun severalTripsAfterFailedLoadIsPicker() {
        // Не загружено и не грузится — загрузка не удалась: выбор остаётся, чтобы переключиться на другую поездку.
        assertEquals(TripLabel.Picker("b", listOf("a", "b")), tripLabel("b", listOf("a", "b"), running = false, loading = false))
    }
}
