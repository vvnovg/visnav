package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Elm327Test {
    @Test fun initAndSpeedCommands() {
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100"), Elm327.INIT)
        assertEquals("010D", Elm327.SPEED)
    }

    @Test fun parsesSpacedResponse() = assertEquals(60, Elm327.parseSpeed("41 0D 3C\r\r>"))

    @Test fun parsesCompactResponse() = assertEquals(60, Elm327.parseSpeed("410D3C>"))

    @Test fun parsesResponseWithEcho() = assertEquals(60, Elm327.parseSpeed("010D\r41 0D 3C\r\r>"))

    @Test fun parsesAfterSearching() = assertEquals(0, Elm327.parseSpeed("SEARCHING...\r41 0D 00\r>"))

    @Test fun parsesMultiEcuResponse() = assertEquals(60, Elm327.parseSpeed("41 0D 3C\r41 0D 3C\r>"))

    @Test fun parsesMaxByte() = assertEquals(255, Elm327.parseSpeed("41 0D FF"))

    @Test fun noSpeedGivesNull() {
        for (r in listOf("NO DATA\r>", "?\r>", "STOPPED\r>", "41 0C 1A F8\r>", "")) assertNull(Elm327.parseSpeed(r), r)
    }

    @Test fun errorsAreRecognized() {
        for (r in listOf("NO DATA\r>", "?\r>", "UNABLE TO CONNECT\r>", "CAN ERROR\r>", "BUS ERROR\r>", "STOPPED\r>", "ERROR\r>")) {
            assertTrue(Elm327.isError(r), r)
        }
    }

    @Test fun normalResponseIsNotError() {
        assertFalse(Elm327.isError("41 0D 3C\r\r>"))
        assertFalse(Elm327.isError("SEARCHING...\r41 0D 00\r>"))
    }
}
