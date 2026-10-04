package io.visnav.app

import kotlin.test.Test
import kotlin.test.assertEquals

class TripLabelTest {
    @Test fun legacyLayoutShowsNothing() {
        assertEquals(TripLabel.None, tripLabel(null, emptyList(), running = false, loaded = true))
    }

    @Test fun singleTripIsText() {
        assertEquals(TripLabel.Text("a"), tripLabel("a", listOf("a"), running = false, loaded = true))
    }

    @Test fun severalTripsIdleIsPicker() {
        assertEquals(TripLabel.Picker("b", listOf("a", "b")), tripLabel("b", listOf("a", "b"), running = false, loaded = true))
    }

    @Test fun severalTripsWhileRunningOrLoadingIsText() {
        assertEquals(TripLabel.Text("b"), tripLabel("b", listOf("a", "b"), running = true, loaded = true))
        assertEquals(TripLabel.Text("b"), tripLabel("b", listOf("a", "b"), running = false, loaded = false))
    }
}
