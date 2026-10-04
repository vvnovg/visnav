package io.visnav.replay

import io.visnav.core.Enu
import io.visnav.core.Geo.haversineM
import io.visnav.core.RoadPack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripPlanTest {
    private val enu = Enu(55.75, 37.6)

    /** Сетка 3×3 улиц с шагом 200 м (узлы (i·200, j·200)); по желанию — изолированная дорога в 1 км к востоку. */
    private fun grid(isolated: Boolean = false): RoadPack {
        val en = ArrayList<DoubleArray>()
        for (j in 0..2) for (i in 0..2) en += doubleArrayOf(i * 200.0, j * 200.0)
        val from = ArrayList<Int>(); val to = ArrayList<Int>()
        for (j in 0..2) for (i in 0..1) { from += j * 3 + i; to += j * 3 + i + 1 }
        for (i in 0..2) for (j in 0..1) { from += j * 3 + i; to += (j + 1) * 3 + i }
        if (isolated) {
            en += doubleArrayOf(1400.0, 0.0); en += doubleArrayOf(1400.0, 200.0)
            from += 9; to += 10
        }
        val ll = en.map { enu.toLatLon(it[0], it[1]) }
        val m = from.size
        return RoadPack(DoubleArray(ll.size) { ll[it][0] }, DoubleArray(ll.size) { ll[it][1] }, LongArray(m) { it + 1L },
            from.toIntArray(), to.toIntArray(), ByteArray(m), ByteArray(m) { 7 })
    }

    private fun ll(e: Double, n: Double): DoubleArray = enu.toLatLon(e, n)

    /** roadpack.bin версии 1 в каталоге — то, что читает trip-plan. */
    private fun writePack(dir: File, p: RoadPack) {
        val n = p.nodeCount; val m = p.edgeCount
        val b = ByteBuffer.allocate(16 + 16 * n + 18 * m).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(RoadPack.MAGIC).putShort(1).putShort(0).putInt(n).putInt(m)
        p.lats.forEach { b.putDouble(it) }; p.lons.forEach { b.putDouble(it) }
        p.way.forEach { b.putLong(it) }; p.from.forEach { b.putInt(it) }; p.to.forEach { b.putInt(it) }
        b.put(p.flags).put(p.cls)
        dir.mkdirs()
        File(dir, "roadpack.bin").writeBytes(b.array())
    }

    private fun spec(p: DoubleArray) = "%.8f,%.8f".format(java.util.Locale.ROOT, p[0], p[1])

    @Test fun planOverViaConcatenatesLegs() {
        val pack = grid()
        val from = ll(0.0, 0.0); val to = ll(400.0, 0.0); val via = ll(400.0, 400.0)
        val direct = assertNotNull(planTrip(pack, listOf(from, to)))
        assertEquals(400.0, direct.lengthM, 1.0)
        val r = assertNotNull(planTrip(pack, listOf(from, via, to)))
        assertTrue(r.lengthM >= 800.0, "length ${r.lengthM}")
        assertTrue(haversineM(r.latLon.first()[0], r.latLon.first()[1], from[0], from[1]) <= 1.0)
        assertTrue(haversineM(r.latLon.last()[0], r.latLon.last()[1], to[0], to[1]) <= 1.0)
    }

    @Test fun unreachableReturnsNull() {
        val pack = grid(isolated = true)
        assertNull(planTrip(pack, listOf(ll(0.0, 0.0), ll(1400.0, 100.0))))
    }

    @Test fun densifyKeepsStepAndEnds() {
        val a = ll(0.0, 0.0); val b = ll(1000.0, 0.0)
        val d = densify(listOf(a, a.copyOf(), b, b.copyOf()))
        assertTrue(d.size >= 41, "size ${d.size}")
        for (i in 1 until d.size) {
            val s = haversineM(d[i - 1][0], d[i - 1][1], d[i][0], d[i][1])
            assertTrue(s <= 25.0 + 1e-6, "step $s")
            assertTrue(s > 1e-6, "duplicate point at $i")
        }
        assertTrue(d.first().contentEquals(a))
        assertEquals(b[0], d.last()[0], 1e-12); assertEquals(b[1], d.last()[1], 1e-12)
    }

    @Test fun gpxHasTimesAndParsesBack() {
        val f = File(createTempDirectory().toFile(), "sub/t.gpx")
        writeGpx(f, listOf(ll(0.0, 0.0), ll(100.0, 0.0), ll(100.0, 50.0)))
        val text = f.readText()
        assertTrue(text.contains("<gpx version=\"1.1\" creator=\"visnav trip-plan\""))
        assertEquals(3, Regex("<trkpt").findAll(text).count())
        val times = Regex("<time>([^<]+)</time>").findAll(text).map { Instant.parse(it.groupValues[1]) }.toList()
        assertEquals(3, times.size)
        assertEquals(Instant.parse("2000-01-01T00:00:00Z"), times[0])
        assertTrue(times[1] > times[0] && times[2] > times[1])
        assertTrue(Regex("<trkpt lat=\"-?\\d+\\.\\d{7}\" lon=\"-?\\d+\\.\\d{7}\">").findAll(text).count() == 3)
    }

    @Test fun cliErrors() {
        val dir = createTempDirectory().toFile()
        val roads = File(dir, "roads"); writePack(roads, grid(isolated = true))
        val out = File(dir, "out.gpx")
        val from = spec(ll(0.0, 0.0)); val to = spec(ll(400.0, 0.0))
        assertEquals(2, tripPlanMain(listOf("--roads", roads.path, "--from", "x", "--to", "1,2", "--out", out.path)))
        assertEquals(2, tripPlanMain(listOf("--roads", roads.path, "--from", from, "--out", out.path)))
        assertEquals(2, tripPlanMain(listOf("--roads", roads.path, "--from", from, "--to", spec(ll(1400.0, 100.0)),
            "--out", out.path)))
        assertFalse(out.exists())
        assertEquals(0, tripPlanMain(listOf("--roads", roads.path, "--from", from, "--to", to,
            "--via", spec(ll(400.0, 400.0)), "--out", out.path)))
        assertTrue(out.isFile)
    }
}
