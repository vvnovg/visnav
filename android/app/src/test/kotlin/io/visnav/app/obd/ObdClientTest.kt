package io.visnav.app.obd

import io.visnav.core.ElmChannel
import io.visnav.core.ObdSpeedEvent
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Адаптер ELM327, отвечающий на INIT и 010D = 60 км/ч. */
private class FakeElm : ElmChannel {
    private var reply: String? = null
    override fun write(cmd: String) {
        reply = when {
            cmd == "ATZ" -> "ELM327 v1.5\r\r>"
            cmd.startsWith("AT") -> "OK\r\r>"
            cmd == "0100" -> "41 00 BE 1F A8 13\r\r>"
            cmd == "010D" -> "41 0D 3C\r\r>"
            else -> "?\r\r>"
        }
    }
    override fun readUntilPrompt(timeoutMs: Long): String? = reply.also { reply = null }
    override fun close() = Unit
}

class ObdClientTest {
    @Test fun registerAfterCancelClosesImmediately() {
        val c = ObdCancel()
        var closed = 0
        val before = Closeable { closed++ }
        c.register(before)
        c.cancel()
        assertEquals(1, closed)
        assertTrue(c.cancelled)
        c.register { closed += 10 }
        assertEquals(11, closed)
    }

    @Test fun callbackExceptionShowsAppErrorAndDoesNotRestart() {
        var ui: ObdUi? = null
        var opens = 0
        val client = ObdClient(
            deviceName = "OBDII",
            open = { opens++; FakeElm() },
            nowMs = { System.nanoTime() / 1e6 },
            onSpeed = { throw IllegalStateException("boom") },
            onUi = { change -> synchronized(this) { ui = change(ui) } },
        )
        client.start()
        assertTrue(client.join(5_000))
        assertEquals("ошибка приложения: boom", ui?.state)
        assertEquals("OBDII", ui?.deviceName)
        assertEquals(1, opens)
    }

    @Test fun speedIsDeliveredUntilStop() {
        val got = CopyOnWriteArrayList<ObdSpeedEvent>()
        var ui: ObdUi? = null
        val client = ObdClient(
            deviceName = null,
            open = { FakeElm() },
            nowMs = { 0.0 },
            onSpeed = { got.add(it) },
            onUi = { change -> synchronized(this) { ui = change(ui) } },
        )
        val e = ObdSpeedEvent(1.0, 60, 40.0)
        client.deliverSpeed(e)
        assertEquals(listOf(e), got)
        assertEquals(60, ui?.lastKmh)
        client.stop()
        client.deliverSpeed(ObdSpeedEvent(2.0, 70, 40.0))
        assertEquals(listOf(e), got)
        assertEquals(60, ui?.lastKmh)
        assertTrue(client.join(100)) // не запускался
    }
}
