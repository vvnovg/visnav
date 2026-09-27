package io.visnav.replay

import io.visnav.core.AccelEvent
import io.visnav.core.DescriptorLog
import io.visnav.core.DescriptorLogWriter
import io.visnav.core.Enu
import io.visnav.core.FrameRecord
import io.visnav.core.Geo
import io.visnav.core.GyroEvent
import io.visnav.core.Half
import io.visnav.core.LatencyJson
import io.visnav.core.LocEvent
import io.visnav.core.RefPack
import io.visnav.core.SensorEvent
import io.visnav.core.SessionHeader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Синтетическая сессия: машина едет на восток 10 м/с 120 с; эталон каждые 10 м вдоль пути с
 * дескриптором-«отпечатком» своей позиции; кадры 2 Гц несут дескриптор ближайшего эталона.
 */
class ReplayerTest {
    private val enu = Enu(55.75, 37.60)
    private val n = 120
    private val dim = n + 1

    private fun oneHot(i: Int) = FloatArray(dim).also { it[i] = 1f }

    private fun pack(): RefPack {
        val count = n + 1
        val lats = DoubleArray(count); val lons = DoubleArray(count)
        val desc = ShortArray(count * dim)
        for (i in 0 until count) {
            val ll = enu.toLatLon(i * 10.0, 0.0); lats[i] = ll[0]; lons[i] = ll[1]
            desc[i * dim + i] = Half.fromFloat(1f)
        }
        return RefPack(count, dim, lats, lons, FloatArray(count), desc)
    }

    private fun session(dir: File): SessionData {
        val t0 = 1_700_000_000_000L
        val sensors = mutableListOf<SensorEvent>()
        val frames = mutableListOf<FrameRecord>()
        val descFile = File(dir, "s.desc")
        DescriptorLogWriter(descFile, dim).use { w ->
            for (step in 0..12_000) { // 100 Гц × 120 с
                val tMs = t0 + step * 10.0
                sensors += AccelEvent(tMs, 0f, 0f, 9.81f + 0.3f * kotlin.math.sin(step.toFloat())) // вибрация едущей машины
                sensors += GyroEvent(tMs, 0f, 0f, 0f)
                val eMeters = step * 0.1
                if (step % 100 == 0) {
                    val ll = enu.toLatLon(eMeters, 0.0)
                    sensors += LocEvent(tMs, ll[0], ll[1], 3f, 10f, 0.3f, 90f, 2f)
                }
                if (step % 50 == 0) {
                    val t = t0 + step * 10L
                    frames += FrameRecord(tMs = t, mode = "gps", gps = null, prior = null, top = emptyList(),
                        fix = null, latMs = LatencyJson(0.0, 0.0, 0.0))
                    w.write(t, oneHot(minOf(n, (eMeters / 10.0).toInt())))
                }
            }
        }
        val header = SessionHeader(model = "m", refpackCreatedAt = "c", device = "d", startedMs = t0, mode = "gps")
        return SessionData(header, frames, sensors.sortedBy { it.tMs }, DescriptorLog.read(descFile))
    }

    private fun truthErrorM(p: TrajPoint, t0: Long): Double {
        val e = (p.tMs - t0) / 1000.0 * 10.0
        val ll = enu.toLatLon(e, 0.0)
        return Geo.haversineM(ll[0], ll[1], p.lat, p.lon)
    }

    @Test fun visualReplayTracksThroughGpsOutage() {
        val dir = createTempDir()
        val s = session(dir)
        val t0 = s.header.startedMs
        val outage = Outage(t0 + 30_000, t0 + 110_000)
        val points = Replayer(pack(), ReplayConfig()).run(s, listOf(outage))
        val inOutage = points.filter { it.inOutage }
        assertTrue(inOutage.size > 100)
        val errors = inOutage.map { truthErrorM(it, t0) }.sorted()
        assertTrue(errors[errors.size * 95 / 100] <= 15.0, "P95=${errors[errors.size * 95 / 100]}")
        assertTrue(inOutage.count { it.visAccepted == true } > inOutage.size / 2)
    }

    @Test fun deadReckoningWithoutVisualKeepsHeadingOnStraightRoad() {
        val dir = createTempDir()
        val s = session(dir)
        val t0 = s.header.startedMs
        val points = Replayer(pack(), ReplayConfig(visual = false)).run(s, listOf(Outage(t0 + 30_000, t0 + 110_000)))
        val last = points.last { it.inOutage }
        val dist = (last.tMs - (t0 + 30_000)) / 1000.0 * 10.0
        assertTrue(truthErrorM(last, t0) / dist * 100 <= 3.0)
        assertTrue(points.filter { it.inOutage }.all { it.visSim == null })
    }

    @Test fun loadsSessionFilesAndWritesTrajectory() {
        val dir = createTempDir()
        val prefix = File(dir, "session-1-gps")
        File("$prefix.jsonl").writeText(
            io.visnav.core.LogJson.line(SessionHeader(model = "m", refpackCreatedAt = "c", device = "d", startedMs = 1, mode = "gps")) + "\n" +
                io.visnav.core.LogJson.line(FrameRecord(tMs = 5, mode = "gps", gps = null, prior = null, top = emptyList(), fix = null, latMs = LatencyJson(0.0, 0.0, 0.0))) + "\n"
        )
        io.visnav.core.SensorLogger(File("$prefix.sensors.jsonl")).use { it.header(1); it.event(GyroEvent(2.0, 0f, 0f, 0f)) }
        DescriptorLogWriter(File("$prefix.desc"), 2).use { it.write(5, floatArrayOf(1f, 0f)) }
        val s = SessionData.load(prefix)
        assertEquals(1, s.frames.size); assertEquals(1, s.sensors.size); assertEquals(1, s.descriptors.times.size)

        val out = File(dir, "traj.jsonl")
        TrajectoryWriter.write(out, s, ReplayConfig(), listOf(Outage(10, 20)),
            listOf(TrajPoint(5, 55.75, 37.6, 4.0, false, null, null)))
        val lines = out.readLines()
        assertEquals("{\"type\":\"replay\",\"visual\":true,\"outages\":[[10,20]],\"session_started_ms\":1,\"refpack_created_at\":\"c\"}", lines[0])
        assertEquals("{\"t_ms\":5,\"lat\":55.75,\"lon\":37.6,\"sigma_m\":4.0,\"outage\":false,\"vis_sim\":null,\"vis_ok\":null}", lines[1])
    }

    @Test fun zeroGnssAccuracyFieldsAreFlooredNotThrown() {
        val dir = createTempDir()
        val t0 = 1_700_000_000_000L
        val sensors = mutableListOf<SensorEvent>()
        for (step in 0..2000) { // 100 Гц × 20 с
            val tMs = t0 + step * 10.0
            sensors += AccelEvent(tMs, 0f, 0f, 9.81f)
            sensors += GyroEvent(tMs, 0f, 0f, 0f)
            if (step % 100 == 0) {
                val eMeters = step * 0.1
                val ll = enu.toLatLon(eMeters, 0.0)
                // spd_acc = 0 and brg_acc = 0: devices sometimes report zero accuracy.
                sensors += LocEvent(tMs, ll[0], ll[1], 3f, 10f, 0f, 90f, 0f)
            }
        }
        val descFile = File(dir, "z.desc")
        DescriptorLogWriter(descFile, dim).use { }
        val header = SessionHeader(model = "m", refpackCreatedAt = "c", device = "d", startedMs = t0, mode = "gps")
        val s = SessionData(header, emptyList(), sensors.sortedBy { it.tMs }, DescriptorLog.read(descFile))
        // Must not throw Ekf2d's require(sigma > 0 && sigma.isFinite()).
        Replayer(pack(), ReplayConfig()).run(s, emptyList())
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("replay").toFile()
}
