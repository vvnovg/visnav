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

    @Test fun compactNonSpeedResponseIsNotMisread() = assertNull(Elm327.parseSpeed("4100B410D3C0\r>"))

    @Test fun linesAreNotStitched() = assertNull(Elm327.parseSpeed("41 00 BE 3F A8 41\r0D 3C\r>"))

    @Test fun parsesCan11BitHeader() {
        assertEquals(60, Elm327.parseSpeed("7E8 03 41 0D 3C\r>"))
        assertEquals(60, Elm327.parseSpeed("7E803410D3C"))
    }

    @Test fun parsesCan29BitHeader() = assertEquals(60, Elm327.parseSpeed("18 DA F1 10 03 41 0D 3C"))

    @Test fun parsesIsoHeaderWithChecksum() = assertEquals(60, Elm327.parseSpeed("48 6B 10 41 0D 3C A2"))

    @Test fun canHeaderWithWrongPciIsRejected() = assertNull(Elm327.parseSpeed("7E8 0B 41 0D 3C\r>"))

    @Test fun valueMustBeOneByte() {
        assertNull(Elm327.parseSpeed("41 0D 3\r>"))
        assertNull(Elm327.parseSpeed("41 0D 3C 00\r>"))
    }

    @Test fun parsesLowercaseAndCrLf() {
        assertEquals(60, Elm327.parseSpeed("41 0d 3c\r\n>"))
        assertEquals(60, Elm327.parseSpeed("010D\r\n41 0D 3C\r\n\r\n>"))
        assertEquals(60, Elm327.parseSpeed("7e8 03 41 0d 3c\r\n>"))
    }

    @Test fun firstEcuWinsWhenValuesDiffer() = assertEquals(60, Elm327.parseSpeed("41 0D 3C\r41 0D 3D\r>"))

    @Test fun moreErrorsAreRecognized() {
        for (r in listOf("BUFFER FULL\r>", "BUS BUSY\r>", "ACT ALERT\r>", "LP ALERT\r>", "LV RESET\r>",
            "ELM327 v1.5\r\r>", "ERR94\r>", "err7f\r\n>")) {
            assertTrue(Elm327.isError(r), r)
        }
        assertFalse(Elm327.isError("41 0D 3C\r>"))
        assertFalse(Elm327.isError("ERR9\r>"))
    }

    @Test fun resetIsRecognized() {
        assertTrue(Elm327.isReset("ELM327 v1.5\r\r>"))
        assertTrue(Elm327.isReset("\r\rELM327 v2.1\r>"))
        assertTrue(Elm327.isReset("LV RESET\r>"))
        assertFalse(Elm327.isReset("41 0D 3C\r>"))
        assertFalse(Elm327.isReset("NO DATA\r>"))
        assertFalse(Elm327.isReset("OK\r>"))
    }

    @Test fun canFramesWithPaddingAreParsed() {
        for (pad in listOf("00", "AA", "55")) {
            assertEquals(60, Elm327.parseSpeed("7E8 03 41 0D 3C $pad $pad $pad $pad\r>"), pad)
            assertEquals(60, Elm327.parseSpeed("18 DA F1 10 03 41 0D 3C $pad $pad $pad $pad\r>"), pad)
            assertEquals(60, Elm327.parseSpeed("7E803410D3C$pad$pad$pad$pad\r>"), pad)
        }
    }

    @Test fun isoHeaderRequires6BOrF1() {
        assertNull(Elm327.parseSpeed("41 00 BE 41 0D 3C\r>"))
        assertNull(Elm327.parseSpeed("4100BE410D3C\r>"))
        assertNull(Elm327.parseSpeed("41 01 00 41 0D 3C 00\r>"))
        assertEquals(60, Elm327.parseSpeed("41 6B 10 41 0D 3C 7F\r>"))
        assertEquals(60, Elm327.parseSpeed("83 F1 11 41 0D 3C 0A\r>"))
        assertEquals(60, Elm327.parseSpeed("486B10410D3CA2\r>"))
    }
}
