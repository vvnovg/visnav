package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
        val a = ev(10.0); val b = ev(10.0); val c = ev(10.0)
        r.push(a); r.push(b); r.push(c)
        val out = r.drainList(10)
        assertSame(a, out[0]); assertSame(b, out[1]); assertSame(c, out[2])
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

    @Test fun sensorBeforeFrameOnEqualTimeRegardlessOfArrival() {
        val r = EventReorderer(0)
        val f = ReorderItem.Frame(10, null); val e = ev(10.0)
        r.push(f); r.push(e)
        val out = r.drainList(10)
        assertSame(e, out[0]); assertSame(f, out[1])
    }

    @Test fun overflowDropsOldestAndCounts() {
        val r = EventReorderer(0, maxQueued = 3)
        for (t in listOf(5.0, 1.0, 4.0, 2.0, 3.0)) r.push(ev(t))
        assertEquals(2, r.dropped)
        assertEquals(listOf(3.0, 4.0, 5.0), r.drainList(100).times())
    }

    @Test fun latenessStatsPerSource() {
        val r = EventReorderer(0)
        r.push(ev(1000.0), 1100); r.push(ev(2000.0), 2300); r.push(ev(3000.0), 3200)
        val imu = r.latenessSnapshotAndReset().getValue("imu")
        assertEquals(LatenessStats(3, 200.0, 300.0, 300.0), imu)
    }

    @Test fun snapshotResetsWindow() {
        val r = EventReorderer(0)
        r.push(ev(1000.0), 1100)
        assertEquals(1, r.latenessSnapshotAndReset().getValue("imu").n)
        assertEquals(null, r.latenessSnapshotAndReset()["imu"])
    }

    @Test fun oldPushRecordsZeroLateness() {
        val r = EventReorderer(0)
        r.push(ev(1000.0))
        assertEquals(LatenessStats(1, 0.0, 0.0, 0.0), r.latenessSnapshotAndReset().getValue("imu"))
    }

    @Test fun sourcesMappedByEventType() {
        val r = EventReorderer(0)
        r.push(ReorderItem.Frame(1, null), 11)
        r.push(ReorderItem.Sensor(LocEvent(1.0, 55.0, 37.0, 3f, null, null, null, null)), 21)
        r.push(ReorderItem.Sensor(GnssStatusEvent(1.0, 10, 8, 30f)), 31)
        r.push(ReorderItem.Sensor(AccelEvent(1.0, 0f, 0f, 0f)), 41)
        r.push(ReorderItem.Sensor(GyroUncalEvent(1.0, 0f, 0f, 0f, 0f, 0f, 0f)), 51)
        r.push(ReorderItem.Sensor(AgcEvent(1.0, 1f, 1)), 61)
        r.push(ReorderItem.Sensor(ClockEvent(1.0, 1, 1, 1)), 71)
        val s = r.latenessSnapshotAndReset()
        assertEquals(10.0, s.getValue("frame").max)
        assertEquals(20.0, s.getValue("gnss_fix").max)
        assertEquals(30.0, s.getValue("gnss_status").max)
        assertEquals(LatenessStats(2, 40.0, 50.0, 50.0), s.getValue("imu"))
        assertEquals(60.0, s.getValue("agc").max)
        assertEquals(70.0, s.getValue("other").max)
    }

    @Test fun delayIsPublic() {
        assertEquals(800L, EventReorderer(800).delayMs)
    }
}
