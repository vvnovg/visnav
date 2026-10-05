package io.visnav.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElmSessionTest {
    private val ok = "OK\r\r>"
    private val initOk = listOf("ELM327 v1.5\r\r>", ok, ok, ok, ok, ok, "41 00 BE 3E B8 11\r\r>")
    private val speed60 = "41 0D 3C\r\r>"

    /** Поддельные часы, sleep и очередь каналов/ошибок открытия; пустая очередь — stop(). */
    private class Rig(private val latencyMs: Double = 40.0) {
        var now = 1000.0
        val sleeps = mutableListOf<Long>()
        val events = mutableListOf<ObdSpeedEvent>()
        val states = mutableListOf<Pair<ElmState, String?>>()
        val opens = ArrayDeque<Any>()  // FakeChannel или Throwable
        lateinit var session: ElmSession

        inner class FakeChannel(vararg responses: String?) : ElmChannel {
            val queue = ArrayDeque(responses.toList())
            val writes = mutableListOf<String>()
            val writeTimes = mutableListOf<Double>()
            val timeouts = mutableListOf<Long>()
            var closed = false

            override fun write(cmd: String) {
                writes += cmd
                writeTimes += now
            }

            override fun readUntilPrompt(timeoutMs: Long): String? {
                timeouts += timeoutMs
                if (queue.isEmpty()) {
                    session.stop()
                    return null
                }
                val r = queue.removeFirst()
                now += if (r == null) timeoutMs.toDouble() else latencyMs
                return r
            }

            override fun close() {
                closed = true
            }
        }

        fun channel(vararg responses: String?) = FakeChannel(*responses).also { opens += it }

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
                onSpeed = { events += it },
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
        val ch = rig.channel(*initOk.toTypedArray(), speed60, speed60, speed60)
        rig.run()

        assertEquals(Elm327.INIT + List(4) { Elm327.SPEED }, ch.writes)
        assertEquals(listOf(3000L, 1000, 1000, 1000, 1000, 1000, 10_000) + List(4) { 1000L }, ch.timeouts)
        assertEquals(3, rig.events.size)
        assertTrue(rig.events.all { it.kmh == 60 && it.rttMs == 40.0 })
        // t0 опросов: 1000 + 7·40 = 1280, далее шаг minIntervalMs = 200
        val pollT0 = ch.writeTimes.drop(Elm327.INIT.size)
        assertEquals(listOf(1280.0, 1480.0, 1680.0, 1880.0), pollT0)
        assertEquals(listOf(1300.0, 1500.0, 1700.0), rig.events.map { it.tMs })
        assertEquals(listOf(160L, 160L, 160L), rig.sleeps)
        assertEquals(
            listOf(ElmState.CONNECTING, ElmState.INIT, ElmState.SEARCHING, ElmState.POLLING, ElmState.STOPPED),
            rig.stateSeq(),
        )
    }

    @Test fun searchingThenPids() {
        val rig = Rig()
        val ch = rig.channel(
            *initOk.dropLast(1).toTypedArray(), "SEARCHING...\r41 00 BE 3E B8 11\r\r>", speed60,
        )
        rig.run()

        assertTrue(ElmState.POLLING in rig.stateSeq())
        assertEquals(Elm327.SPEED, ch.writes[Elm327.INIT.size])
        assertEquals(1, rig.events.size)
    }

    @Test fun noDataOn0100RetriesAfter5s() {
        for (bad in listOf("NO DATA\r\r>", "SEARCHING...\rUNABLE TO CONNECT\r\r>")) {
            val rig = Rig()
            val ch = rig.channel(*initOk.dropLast(1).toTypedArray(), bad, initOk.last(), speed60)
            rig.run()

            val seq = rig.stateSeq()
            assertTrue(seq.indexOf(ElmState.NO_DATA) in 0 until seq.indexOf(ElmState.POLLING), bad)
            assertEquals(listOf(5000L), rig.longSleeps(), bad)
            assertEquals(Elm327.INIT + "0100" + Elm327.SPEED + Elm327.SPEED, ch.writes, bad)
            assertEquals(1, rig.events.size, bad)
        }
    }

    @Test fun fiveFailuresReconnectWithBackoffResetBySuccess() {
        val rig = Rig()
        val ch1 = rig.channel(*initOk.toTypedArray(), null, null, null, null, null)
        val noData = "NO DATA\r\r>"
        val ch2 = rig.channel(*initOk.toTypedArray(), noData, noData, noData, noData, noData)
        val ch3 = rig.channel(*initOk.toTypedArray(), speed60, null, null, null, null, null)
        val ch4 = rig.channel(*initOk.toTypedArray())
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

    @Test fun adapterResetWhilePollingReinitsOnSameChannel() {
        for (reset in listOf("\r\rELM327 v1.5\r\r>", "LV RESET\r\r>")) {
            val rig = Rig()
            val ch = rig.channel(*initOk.toTypedArray(), speed60, reset, *initOk.toTypedArray(), speed60)
            rig.run()

            assertEquals(Elm327.INIT + List(2) { Elm327.SPEED } + Elm327.INIT + List(2) { Elm327.SPEED }, ch.writes, reset)
            assertEquals(2, rig.events.size, reset)
            assertEquals(2, rig.stateSeq().count { it == ElmState.INIT }, reset)
            assertTrue(ElmState.RECONNECT_WAIT !in rig.stateSeq(), reset)
            assertTrue(rig.opens.isEmpty(), reset)
        }
    }

    @Test fun openFailuresGrowBackoff() {
        val rig = Rig()
        repeat(4) { rig.opens += java.io.IOException("connect failed") }
        rig.run()

        assertEquals(listOf(2000L, 5000L, 10_000L, 10_000L), rig.sleeps)
        assertTrue(rig.states.any { it.first == ElmState.ERROR && it.second?.contains("connect failed") == true })
        assertEquals(ElmState.STOPPED, rig.stateSeq().last())
    }

    @Test fun stopFromAnotherThreadEndsRunWithinOneTimeout() {
        val states = CopyOnWriteArrayList<ElmState>()
        val polling = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val inits = ArrayDeque(initOk)
        val ch = object : ElmChannel {
            override fun write(cmd: String) = Unit
            override fun readUntilPrompt(timeoutMs: Long): String? {
                inits.removeFirstOrNull()?.let { return it }
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
        val t = Thread { session.run() }
        t.start()
        assertTrue(polling.await(2, TimeUnit.SECONDS))
        session.stop()
        t.join(1500)
        assertFalse(t.isAlive)
        assertEquals(ElmState.STOPPED, states.last())
    }
}
