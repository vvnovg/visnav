package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class InstructionsTest {
    private fun m(t: ManeuverType, street: String? = null, exit: Int = 0) = Maneuver(t, 0.0, 0.0, 0.0, street, exit)

    @Test fun distances() {
        assertEquals("80 м", Instructions.distanceText(78.0))
        assertEquals("10 м", Instructions.distanceText(3.0))
        assertEquals("350 м", Instructions.distanceText(362.0))
        assertEquals("1,2 км", Instructions.distanceText(1234.0))
        assertEquals("1 км", Instructions.distanceText(975.0))
        assertEquals("1 км", Instructions.distanceText(995.0))
        assertEquals("1 км", Instructions.distanceText(1000.0))
        assertEquals("1 км", Instructions.distanceText(1020.0))
        assertEquals("1,3 км", Instructions.distanceText(1260.0))
        assertEquals("2 км", Instructions.distanceText(2000.0))
        assertEquals("950 м", Instructions.distanceText(940.0))
    }

    @Test fun prompts() {
        assertEquals("Через 300 м поверните направо — Тверская улица",
            Instructions.prompt(m(ManeuverType.RIGHT, "Тверская улица"), 310.0))
        assertEquals("Держитесь левее", Instructions.prompt(m(ManeuverType.SLIGHT_LEFT), null))
        assertEquals("Через 150 м на круговом движении второй съезд",
            Instructions.prompt(m(ManeuverType.ROUNDABOUT, exit = 2), 150.0))
        assertEquals("Через 200 м — пункт назначения", Instructions.prompt(m(ManeuverType.ARRIVE), 200.0))
        assertEquals("Вы прибыли", Instructions.prompt(m(ManeuverType.ARRIVE), null))
        assertEquals("12-й", Instructions.ordinal(12))
    }

    @Test fun shortActions() {
        val expected = mapOf(
            ManeuverType.LEFT to "налево", ManeuverType.RIGHT to "направо", ManeuverType.SLIGHT_LEFT to "левее",
            ManeuverType.SLIGHT_RIGHT to "правее", ManeuverType.SHARP_LEFT to "резко налево",
            ManeuverType.SHARP_RIGHT to "резко направо", ManeuverType.UTURN to "разворот", ManeuverType.CONTINUE to "прямо",
            ManeuverType.ROUNDABOUT to "на кольце второй съезд",
        )
        for ((t, text) in expected) assertEquals(text, Instructions.shortAction(m(t, "Улица", exit = 2)))
    }
}
