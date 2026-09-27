package io.visnav.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionLoggerTest {
    @Test fun writesHeaderThenFramesAsJsonLines() {
        val f = File.createTempFile("session", ".jsonl")
        SessionLogger(f).use { log ->
            log.header(SessionHeader(model = "m", refpackCreatedAt = "c", device = "d", startedMs = 1, mode = "gps"))
            repeat(12) {
                log.frame(FrameRecord(tMs = it.toLong(), mode = "gps", gps = null, prior = null, top = emptyList(),
                    fix = null, latMs = LatencyJson(0.0, 0.0, 0.0)))
            }
        }
        val lines = f.readLines()
        assertEquals(13, lines.size)
        assertEquals(true, lines[0].contains("\"type\":\"session\""))
        assertEquals(true, lines[12].contains("\"t_ms\":11"))
    }
}
