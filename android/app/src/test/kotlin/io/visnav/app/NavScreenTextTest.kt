package io.visnav.app

import io.visnav.core.NavMode
import kotlin.test.Test
import kotlin.test.assertEquals

class NavScreenTextTest {
    @Test fun distanceNowBelow30m() {
        assertEquals("Сейчас", maneuverDistanceText(29.9))
        assertEquals("30 м", maneuverDistanceText(30.0))
        assertEquals("1,2 км", maneuverDistanceText(1200.0))
    }

    @Test fun remainingKmAndMinutesScaledByRemainingShare() {
        // Маршрут 10 км, 20 мин; осталось 2,5 км → ~5 мин.
        assertEquals("Осталось 2,5 км · ~5 мин", remainingText(2500.0, 10.0, 20.0))
        assertEquals("Осталось 0,1 км · ~1 мин", remainingText(100.0, 10.0, 20.0))
        assertEquals("Осталось 2,5 км", remainingText(2500.0, null, null))
    }

    @Test fun modeColoursFromSpec() {
        assertEquals("#2E7D32", modeHex(NavMode.GNSS))
        assertEquals("#2E7D32", modeHex(NavMode.FUSED))
        assertEquals("#1565C0", modeHex(NavMode.VISUAL))
        assertEquals("#EF6C00", modeHex(NavMode.DEAD_RECKONING))
    }
}
