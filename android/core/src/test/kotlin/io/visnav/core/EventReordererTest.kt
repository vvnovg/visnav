package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class EventReordererTest {
    private fun ev(t: Double) = ReorderItem.Sensor(GyroEvent(t, 0f, 0f, 0f))
    private fun List<ReorderItem>.times() = map { it.tMs }
    private fun EventReorderer.drainList(now: Long): List<ReorderItem> =
        ArrayList<ReorderItem>().also { out -> drain(now) { out.add(it) } }

    @Test fun releasesInTimeOrder() {
        val r = EventReorderer(1000)
        r.push(ev(300.0)); r.push(ReorderItem.Frame(100, null)); r.push(ev(200.0))
        assertEquals(listOf(100.0, 200.0, 300.0), r.drainList(5000).times())
    }

    @Test fun delayBoundaryIsInclusive() {
        val r = EventReorderer(1000)
        r.push(ev(500.0)); r.push(ev(501.0))
        assertEquals(emptyList(), r.drainList(1499).times())
        assertEquals(listOf(500.0), r.drainList(1500).times())
        assertEquals(listOf(501.0), r.drainList(1501).times())
    }

    @Test fun equalTimesKeepInsertionOrder() {
        val r = EventReorderer(0)
        val a = ev(10.0); val b = ReorderItem.Frame(10, null); val c = ev(10.0)
        r.push(a); r.push(b); r.push(c)
        val out = r.drainList(10)
        assertEquals(listOf(a, b, c), out)
        assert(out[0] === a && out[1] === b && out[2] === c)
    }

    @Test fun drainAllReleasesEverything() {
        val r = EventReorderer(1_000_000)
        r.push(ev(2.0)); r.push(ev(1.0))
        val out = ArrayList<ReorderItem>()
        r.drainAll { out.add(it) }
        assertEquals(listOf(1.0, 2.0), out.times())
        assertEquals(emptyList(), r.drainList(Long.MAX_VALUE / 2).times())
    }

    @Test fun lateItemsReleasedOnNextDrainInArrivalOrderAndCounted() {
        val r = EventReorderer(100)
        r.push(ev(1000.0))
        assertEquals(listOf(1000.0), r.drainList(1100).times())
        assertEquals(0, r.late)
        r.push(ev(900.0)); r.push(ev(800.0)); r.push(ev(5000.0))
        assertEquals(listOf(900.0, 800.0), r.drainList(1200).times())
        assertEquals(2, r.late)
        assertEquals(listOf(5000.0), r.drainList(5100).times())
    }
}
