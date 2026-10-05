package io.visnav.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElmSessionTest {
    /** Кусок вывода адаптера; late — первое чтение не успевает (таймаут), кусок приходит к следующему. */
    private class Chunk(val text: String, var late: Boolean = false)

    private fun r(vararg texts: String): List<Chunk> = texts.map { Chunk(it) }
    private fun late(text: String): List<Chunk> = listOf(Chunk(text, late = true))
    private val silent: List<Chunk> = emptyList()

    private val ok = "OK\r\r>"
    private val banner = "ELM327 v1.5\r\r>"
    private val pidsReply = "41 00 BE 3E B8 11\r\r>"
    private val initOk: List<List<Chunk>> get() = listOf(r(banner), r(ok), r(ok), r(ok), r(ok), r(ok), r(pidsReply))
    private fun speed(kmh: Int) = r("41 0D %02X\r\r>".format(kmh))

    /**
     * Поддельный адаптер: каждая запись выкладывает в буфер вывода очередной шаг сценария, чтение берёт из буфера.
     * Пустой буфер — таймаут (часы сдвигаются на timeoutMs). Сценарий кончился при записи или открытии — stop().
     */
    private class Rig(private val latencyMs: Double = 40.0) {
        var now = 1000.0
        val sleeps = mutableListOf<Long>()
        val events = mutableListOf<ObdSpeedEvent>()
        val states = mutableListOf<Pair<ElmState, String?>>()
        val opens = ArrayDeque<Any>()  // FakeChannel или Throwable
        var onSpeedHook: (ObdSpeedEvent) -> Unit = {}
        lateinit var session: ElmSession

        inner class FakeChannel(steps: List<List<Chunk>>) : ElmChannel {
            val script = ArrayDeque(steps)
            val output = ArrayDeque<Chunk>()
            val writes = mutableListOf<String>()
            val writeTimes = mutableListOf<Double>()
            val timeouts = mutableListOf<Long>()
            var closed = false

            override fun write(cmd: String) {
                writes += cmd
                writeTimes += now
                val step = script.removeFirstOrNull()
                if (step == null) session.stop() else output.addAll(step)
            }

            override fun readUntilPrompt(timeoutMs: Long): String? {
                timeouts += timeoutMs
                val c = output.firstOrNull()
                if (c == null || c.late) {
                    c?.late = false
                    now += timeoutMs
                    return null
                }
                output.removeFirst()
                now += latencyMs
                return c.text
            }

            override fun close() {
                closed = true
            }
        }

        fun channel(steps: List<List<Chunk>>) = FakeChannel(steps).also { opens += it }

        fun run(): Rig {
            session = ElmSession(
                open = {
                    when (val o = opens.removeFirstOrNull()) {
                        null -> { session.stop(); throw IllegalStateException("no more channels") }
                        is Throwable -> throw o
                        else -> o as ElmChannel
                    }
                },
                nowMs = { now },
                sleep = { ms -> sleeps += ms; now += ms },
                onSpeed = { events += it; onSpeedHook(it) },
                onState = { s, msg -> states += s to msg },
            )
            session.run()
            return this
        }

        fun stateSeq() = states.map { it.first }
        fun longSleeps() = sleeps.filter { it >= 1000 }
    }

    @Test fun normalPolling() {
        val rig = Rig()
        val ch = rig.channel(initOk + listOf(speed(60), speed(60), speed(60)))
        rig.run()

        assertEquals(Elm327.INIT + List(4) { Elm327.SPEED }, ch.writes)
        // drain перед ATZ (300), затем таймауты INIT и опросов
        assertEquals(listOf(300L, 3000, 1000, 1000, 1000, 1000, 1000, 10_000) + List(4) { 1000L }, ch.timeouts)
        assertEquals(3, rig.events.size)
        assertTrue(rig.events.all { it.kmh == 60 && it.rttMs == 40.0 })
        // t0 опросов: 1000 + 300 (drain) + 7·40 = 1580, далее шаг minIntervalMs = 200
        val pollT0 = ch.writeTimes.drop(Elm327.INIT.size)
        assertEquals(listOf(1580.0, 1780.0, 1980.0, 2180.0), pollT0)
        assertEquals(listOf(1600.0, 1800.0, 2000.0), rig.events.map { it.tMs })
        assertEquals(listOf(160L, 160L, 160L), rig.sleeps)
        assertEquals(
            listOf(ElmState.CONNECTING, ElmState.INIT, ElmState.SEARCHING, ElmState.POLLING, ElmState.STOPPED),
            rig.stateSeq(),
        )
    }

    @Test fun searchingThenPids() {
        val rig = Rig()
        val ch = rig.channel(initOk.dropLast(1) + listOf(r("SEARCHING...\r41 00 BE 3E B8 11\r\r>"), speed(60)))
        rig.run()

        assertTrue(ElmState.POLLING in rig.stateSeq())
        assertEquals(Elm327.SPEED, ch.writes[Elm327.INIT.size])
        assertEquals(1, rig.events.size)
    }

    @Test fun noDataOn0100RetriesAfter5s() {
        for (bad in listOf("NO DATA\r\r>", "SEARCHING...\rUNABLE TO CONNECT\r\r>")) {
            val rig = Rig()
            val ch = rig.channel(initOk.dropLast(1) + listOf(r(bad), r(pidsReply), speed(60)))
            rig.run()

            val seq = rig.stateSeq()
            assertTrue(seq.indexOf(ElmState.NO_DATA) in 0 until seq.indexOf(ElmState.POLLING), bad)
            assertEquals(listOf(5000L), rig.longSleeps(), bad)
            assertEquals(Elm327.INIT + "0100" + Elm327.SPEED + Elm327.SPEED, ch.writes, bad)
            assertEquals(1, rig.events.size, bad)
        }
    }

    @Test fun staleSpeedOn0100IsNotTakenForPids() {
        val rig = Rig()
        val ch = rig.channel(initOk.dropLast(1) + listOf(r("41 0D 3C\r\r>"), r(pidsReply), speed(61)))
        rig.run()

        assertEquals(Elm327.INIT + "0100" + Elm327.SPEED + Elm327.SPEED, ch.writes)
        assertEquals(listOf(61), rig.events.map { it.kmh })
    }

    @Test fun fiveFailuresReconnectWithBackoffResetBySuccess() {
        val rig = Rig()
        val noData = r("NO DATA\r\r>")
        val ch1 = rig.channel(initOk + List(5) { silent })
        val ch2 = rig.channel(initOk + List(5) { noData })
        val ch3 = rig.channel(initOk + listOf(speed(60)) + List(5) { silent })
        val ch4 = rig.channel(initOk)
        rig.run()

        assertTrue(ch1.closed && ch2.closed && ch3.closed)
        assertEquals(Elm327.INIT + List(5) { Elm327.SPEED }, ch1.writes)
        assertEquals(Elm327.INIT + List(5) { Elm327.SPEED }, ch2.writes)
        assertEquals(Elm327.INIT + List(6) { Elm327.SPEED }, ch3.writes)
        assertEquals(Elm327.INIT + Elm327.SPEED, ch4.writes)
        // ch1 → 2000, ch2 → 5000; успех на ch3 сбрасывает паузу → снова 2000
        assertEquals(listOf(2000L, 5000L, 2000L), rig.longSleeps())
        assertEquals(3, rig.stateSeq().count { it == ElmState.RECONNECT_WAIT })
        assertEquals(1, rig.events.size)
        assertEquals(ElmState.STOPPED, rig.stateSeq().last())
    }

    @Test fun validButUnparsableReplyCountsAsError() {
        val rig = Rig()
        val ch1 = rig.channel(initOk + List(5) { r("41 0C 1A F8\r\r>") })
        rig.channel(initOk)
        rig.run()

        assertTrue(ch1.closed)
        assertEquals(listOf(2000L), rig.longSleeps())
        assertTrue(rig.events.isEmpty())
    }

    @Test fun timeoutSkipsPause() {
        val rig = Rig()
        rig.channel(initOk + listOf(speed(60), silent, speed(60)))
        rig.run()

        // пауза только после ответов: после таймаута (1000 + drain 300) от t0 прошло больше minIntervalMs
        assertEquals(listOf(160L, 160L), rig.sleeps)
        assertEquals(2, rig.events.size)
    }

    @Test fun lateTailAfterTimeoutIsDrainedNotTakenAsNextReply() {
        val rig = Rig()
        val ch = rig.channel(initOk + listOf(speed(60), late("41 0D 3E\r\r>"), speed(61)))
        rig.run()

        assertEquals(listOf(60, 61), rig.events.map { it.kmh })
        assertEquals(Elm327.INIT + List(4) { Elm327.SPEED }, ch.writes)
    }

    @Test fun adapterResetWhilePollingReinitsOnSameChannel() {
        for (reset in listOf("\r\rELM327 v1.5\r\r>", "LV RESET\r\r>", "\u0000ÿELM327 v1.5\r>")) {
            val rig = Rig()
            val ch = rig.channel(initOk + listOf(speed(60), r(reset)) + initOk + listOf(speed(60)))
            rig.run()

            assertEquals(Elm327.INIT + List(2) { Elm327.SPEED } + Elm327.INIT + List(2) { Elm327.SPEED }, ch.writes, reset)
            assertEquals(2, rig.events.size, reset)
            assertEquals(2, rig.stateSeq().count { it == ElmState.INIT }, reset)
            assertTrue(ElmState.RECONNECT_WAIT !in rig.stateSeq(), reset)
            assertTrue(rig.opens.isEmpty(), reset)
        }
    }

    @Test fun bannerInBufferWithOutstandingReplyDoesNotShiftInit() {
        val rig = Rig()
        // после сброса в буфере баннер, за ним — ответ на ещё не прочитанный 010D (эхо снова включено)
        val ch = rig.channel(initOk + listOf(speed(60), r(banner, "010D\r41 0D 3C\r\r>")) + initOk + listOf(speed(61)))
        rig.run()

        assertEquals(Elm327.INIT + List(2) { Elm327.SPEED } + Elm327.INIT + List(2) { Elm327.SPEED }, ch.writes)
        assertEquals(listOf(60, 61), rig.events.map { it.kmh })
        assertTrue(ElmState.RECONNECT_WAIT !in rig.stateSeq())
    }

    @Test fun fiveResetsInARowReconnect() {
        val rig = Rig()
        val resets = (1..4).flatMap { listOf(r(banner)) + initOk } + listOf(r(banner))
        val ch1 = rig.channel(initOk + resets)
        rig.channel(initOk)
        rig.run()

        assertTrue(ch1.closed)
        assertEquals(5, ch1.writes.count { it == "ATZ" })
        assertEquals(listOf(2000L), rig.longSleeps())
    }

    @Test fun atCommandWithoutOkIsRetriedOnce() {
        val rig = Rig()
        val q = r("?\r\r>")
        val ch = rig.channel(listOf(r(banner), q, r(ok)) + initOk.drop(2) + listOf(speed(60)))
        rig.run()

        assertEquals(listOf("ATZ", "ATE0", "ATE0") + Elm327.INIT.drop(2) + List(2) { Elm327.SPEED }, ch.writes)
        assertEquals(1, rig.events.size)
        assertTrue(ElmState.RECONNECT_WAIT !in rig.stateSeq())
    }

    @Test fun atCommandFailingTwiceReconnects() {
        val rig = Rig()
        val q = r("?\r\r>")
        val ch1 = rig.channel(listOf(r(banner), q, q))
        val ch2 = rig.channel(initOk + listOf(speed(60)))
        rig.run()

        assertTrue(ch1.closed)
        assertEquals(listOf("ATZ", "ATE0", "ATE0"), ch1.writes)
        assertEquals(listOf(2000L), rig.longSleeps())
        assertEquals(Elm327.INIT + List(2) { Elm327.SPEED }, ch2.writes)
    }

    @Test fun atzWithoutBannerIsRetriedOnce() {
        val rig = Rig()
        val ch = rig.channel(listOf(silent) + initOk + listOf(speed(60)))
        rig.run()

        assertEquals(listOf("ATZ") + Elm327.INIT + List(2) { Elm327.SPEED }, ch.writes)
        assertEquals(1, rig.events.size)
    }

    @Test fun openFailuresGrowBackoff() {
        val rig = Rig()
        repeat(4) { rig.opens += java.io.IOException("connect failed") }
        rig.run()

        assertEquals(listOf(2000L, 5000L, 10_000L, 10_000L), rig.sleeps)
        assertTrue(rig.states.any { it.first == ElmState.ERROR && it.second?.contains("connect failed") == true })
        assertEquals(ElmState.STOPPED, rig.stateSeq().last())
    }

    @Test fun callbackExceptionLeavesRunInsteadOfReconnect() {
        val rig = Rig()
        val ch = rig.channel(initOk + listOf(speed(60), speed(60)))
        rig.channel(initOk)
        rig.onSpeedHook = { throw IllegalArgumentException("ui bug") }

        val e = assertFailsWith<IllegalArgumentException> { rig.run() }
        assertEquals("ui bug", e.message)
        assertTrue(ch.closed)
        assertEquals(1, rig.opens.size)  // второй канал не открывался
        assertTrue(rig.longSleeps().isEmpty())
        assertEquals(ElmState.STOPPED, rig.stateSeq().last())
    }

    @Test fun stopFromAnotherThreadEndsRunWithinOneTimeoutAndClearsInterrupt() {
        val states = CopyOnWriteArrayList<ElmState>()
        val polling = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val replies = ArrayDeque<String>()
        val ch = object : ElmChannel {
            override fun write(cmd: String) {
                when {
                    cmd == "ATZ" -> replies += banner
                    cmd == "0100" -> replies += pidsReply
                    cmd.startsWith("AT") -> replies += ok
                }
            }
            override fun readUntilPrompt(timeoutMs: Long): String? {
                replies.removeFirstOrNull()?.let { return it }
                closed.await(timeoutMs, TimeUnit.MILLISECONDS)  // блокируется как настоящий сокет до close()
                return null
            }
            override fun close() = closed.countDown()
        }
        val session = ElmSession(
            open = { ch },
            nowMs = { System.nanoTime() / 1e6 },
            sleep = { Thread.sleep(it) },
            onSpeed = {},
            onState = { s, _ -> states += s; if (s == ElmState.POLLING) polling.countDown() },
        )
        val interruptedAfterRun = AtomicReference<Boolean>()
        val t = Thread { session.run(); interruptedAfterRun.set(Thread.interrupted()) }
        t.start()
        assertTrue(polling.await(5, TimeUnit.SECONDS))
        session.stop()
        t.join(1500)
        assertFalse(t.isAlive)
        assertEquals(ElmState.STOPPED, states.last())
        assertEquals(false, interruptedAfterRun.get())
    }

    @Test fun stopDuringReconnectWaitEndsSleep() {
        val states = CopyOnWriteArrayList<ElmState>()
        val waiting = CountDownLatch(1)
        val session = ElmSession(
            open = { throw java.io.IOException("no adapter") },
            nowMs = { System.nanoTime() / 1e6 },
            sleep = { Thread.sleep(it) },
            onSpeed = {},
            onState = { s, _ -> states += s; if (s == ElmState.RECONNECT_WAIT) waiting.countDown() },
        )
        val interruptedAfterRun = AtomicReference<Boolean>()
        val t = Thread { session.run(); interruptedAfterRun.set(Thread.interrupted()) }
        t.start()
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        session.stop()
        t.join(1000)  // пауза 2000 мс прерывается
        assertFalse(t.isAlive)
        assertEquals(ElmState.STOPPED, states.last())
        assertEquals(false, interruptedAfterRun.get())
    }
}
