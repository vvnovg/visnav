package io.visnav.app.obd

import java.io.IOException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptBufferTest {
    @Test fun appendUntilPromptJoinsChunks() {
        val buf = StringBuilder()
        assertNull(appendUntilPrompt(buf, "41 0D"))
        assertNull(appendUntilPrompt(buf, " 3C\r"))
        assertEquals("41 0D 3C\r\r>", appendUntilPrompt(buf, "\r>"))
        assertEquals("", buf.toString())
    }

    @Test fun appendUntilPromptKeepsTailAfterPrompt() {
        val buf = StringBuilder()
        assertEquals("OK\r>", appendUntilPrompt(buf, "OK\r>41 0D"))
        assertEquals("41 0D", buf.toString())
        assertEquals("41 0D 00\r>", appendUntilPrompt(buf, " 00\r>"))
    }

    @Test fun bufferReturnsEachReplyInOrder() {
        val b = PromptBuffer()
        b.append("OK\r>ELM327 v1.5\r>41 0")
        assertEquals("OK\r>", b.take(0))
        assertEquals("ELM327 v1.5\r>", b.take(0))
        assertNull(b.take(0))
        b.append("D 3C\r>")
        assertEquals("41 0D 3C\r>", b.take(0))
    }

    @Test fun takeWaitsForReplyFromAnotherThread() {
        val b = PromptBuffer()
        val t = thread { Thread.sleep(50); b.append("41 0D 3C\r"); b.append(">") }
        assertEquals("41 0D 3C\r>", b.take(2000))
        t.join()
    }

    @Test fun takeTimesOut() {
        val b = PromptBuffer()
        b.append("41 0D")
        val t0 = System.nanoTime()
        assertNull(b.take(100))
        assertTrue((System.nanoTime() - t0) / 1e6 >= 90)
    }

    @Test fun clearDropsRepliesAndPartial() {
        val b = PromptBuffer()
        b.append("STOPPED\r>41 0")
        b.clear()
        b.append("D 3C\r>")
        assertEquals("D 3C\r>", b.take(0))
    }

    @Test fun drainDiscardsBufferedAndLateOutput() {
        val b = PromptBuffer()
        b.append("NO DATA\r>41 0D")
        val t = thread { Thread.sleep(30); b.append(" 3C\r>") }
        b.drain(200)
        t.join()
        assertNull(b.take(0))
        b.append("OK\r>")
        assertEquals("OK\r>", b.take(0))
    }

    @Test fun failMakesReadsThrow() {
        val b = PromptBuffer()
        b.append("OK\r>")
        b.fail(IOException("socket closed"))
        assertEquals("OK\r>", b.take(0))
        assertFailsWith<IOException> { b.take(100) }
    }
}
