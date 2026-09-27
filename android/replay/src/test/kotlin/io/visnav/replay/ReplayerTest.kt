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

    /**
     * @param outage если задан, используется для двух вещей:
     *   - `gpsShiftNDuringOutageM`: пропадание GPS во внутренних терминах означает, что телефон не
     *     получает исправных сигналов; но иногда устройство продолжает сообщать устаревший/битый
     *     фикс. Такие LocEvent'ы внутри окна `outage` намеренно сдвинуты на север — если `Replayer`
     *     перестанет их игнорировать (`if (inOutage(t)) continue`), фильтр потянется за неверными
     *     координатами и тест это поймает.
     *   - `speedBiasFracBeforeOutage`: скорость в логе GPS завышена на эту долю до начала пропадания,
     *     смещая скоростную составляющую состояния фильтра к началу счисления пути.
     * @param gyroZBias постоянное смещение оси Z гироскопа (рад/с) — с учётом того, что телефон лежит
     *   плоско и лишь вибрирует, эта ось почти совпадает с вертикалью, так что смещение почти целиком
     *   просачивается в оценку скорости поворота курса.
     */
    private fun session(
        dir: File,
        outage: Outage? = null,
        gpsShiftNDuringOutageM: Double = 0.0,
        speedBiasFracBeforeOutage: Double = 0.0,
        gyroZBias: Float = 0f,
    ): SessionData {
        val t0 = 1_700_000_000_000L
        val sensors = mutableListOf<SensorEvent>()
        val frames = mutableListOf<FrameRecord>()
        val descFile = File(dir, "s.desc")
        DescriptorLogWriter(descFile, dim).use { w ->
            for (step in 0..12_000) { // 100 Гц × 120 с
                val tMs = t0 + step * 10.0
                sensors += AccelEvent(tMs, 0f, 0f, 9.81f + 0.3f * kotlin.math.sin(step.toFloat())) // вибрация едущей машины
                sensors += GyroEvent(tMs, 0f, 0f, gyroZBias)
                val eMeters = step * 0.1
                if (step % 100 == 0) {
                    val inOutageWindow = outage != null && outage.contains(tMs)
                    val nOffset = if (inOutageWindow) gpsShiftNDuringOutageM else 0.0
                    val ll = enu.toLatLon(eMeters, nOffset)
                    val beforeOutage = outage == null || tMs < outage.startMs
                    val speed = if (beforeOutage) (10.0 * (1.0 + speedBiasFracBeforeOutage)).toFloat() else 10f
                    // The shifted fix is logged with a plausible (not tiny) accuracy — a real device
                    // reporting a stale/bad fix rarely also reports millimetre-grade accuracy for it.
                    // Chosen so the residual would pass Ekf2d's chi-square gate (and so actually pull
                    // the filter 200 m north) if the replayer failed to skip it as an outage sample.
                    val accM = if (inOutageWindow) 60f else 3f
                    sensors += LocEvent(tMs, ll[0], ll[1], accM, speed, 0.3f, 90f, 2f)
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

    private fun p95(errors: List<Double>): Double {
        val sorted = errors.sorted()
        return sorted[sorted.size * 95 / 100]
    }

    @Test fun visualReplayTracksThroughGpsOutage() {
        val dir = createTempDir()
        val outage = Outage(1_700_000_000_000L + 30_000, 1_700_000_000_000L + 110_000)
        val s = session(dir, outage = outage)
        val t0 = s.header.startedMs
        val points = Replayer(pack(), ReplayConfig()).run(s, listOf(outage))
        val inOutage = points.filter { it.inOutage }
        assertTrue(inOutage.size > 100)
        val errors = inOutage.map { truthErrorM(it, t0) }.sorted()
        assertTrue(errors[errors.size * 95 / 100] <= 15.0, "P95=${errors[errors.size * 95 / 100]}")
        assertTrue(inOutage.count { it.visAccepted == true } > inOutage.size / 2)
    }

    @Test fun deadReckoningWithoutVisualKeepsHeadingOnStraightRoad() {
        val dir = createTempDir()
        val outage = Outage(1_700_000_000_000L + 30_000, 1_700_000_000_000L + 110_000)
        // GPS logged during the outage is shifted 200 m north of the truth: a real device that keeps
        // reporting a stale/bad fix while it has no real signal. It is also logged with a plausible
        // (not tiny) accuracy — a device reporting a bad fix rarely also reports millimetre-grade
        // accuracy for it — so the residual would pass Ekf2d's chi-square gate and actually pull the
        // filter 200 m north if `Replayer` failed to skip it as an outage sample. With `visual = false`
        // there is nothing else in this test to mask that pull, so it is the strongest check that
        // `if (inOutage(t)) continue` is intact.
        val s = session(dir, outage = outage, gpsShiftNDuringOutageM = 200.0)
        val t0 = s.header.startedMs
        val points = Replayer(pack(), ReplayConfig(visual = false)).run(s, listOf(outage))
        val beforeOutage = points.last { !it.inOutage && it.tMs < outage.startMs }
        val last = points.last { it.inOutage }
        val dist = (last.tMs - outage.startMs) / 1000.0 * 10.0
        val inOutageErrors = points.filter { it.inOutage }.map { truthErrorM(it, t0) }
        assertTrue(truthErrorM(last, t0) / dist * 100 <= 3.0)
        assertTrue(points.filter { it.inOutage }.all { it.visSim == null })
        // Stays near truth despite the shifted GPS logged during the outage: a hard absolute cap, well
        // under the ~200 m the filter would snap to if the shifted fix were consumed instead of skipped.
        assertTrue(
            inOutageErrors.max() <= 30.0,
            "max in-outage error=${inOutageErrors.max()} should stay near truth, not follow the " +
                "200 m-shifted GPS logged during the outage",
        )
        // Losing GPS for tens of seconds must inflate the filter's own uncertainty a lot: no position
        // update landed since `outage.startMs`, so sigma should have grown well past its pre-outage
        // value (guards against a Replayer that silently keeps predicting P as if still fixed).
        assertTrue(
            last.sigmaM >= 3 * beforeOutage.sigmaM,
            "last in-outage sigma=${last.sigmaM} should be >= 3x pre-outage sigma=${beforeOutage.sigmaM}",
        )
    }

    @Test fun visualCorrectsBiasedDeadReckoningWhileDeadReckoningAloneExceedsNfr1() {
        val dir = createTempDir()
        val outage = Outage(1_700_000_000_000L + 30_000, 1_700_000_000_000L + 110_000)
        // A modest, realistic double bias that a real receiver/IMU pair can produce: GPS speed
        // reported 5% high right up to the outage, plus a small constant gyro-Z bias throughout.
        // Pure dead reckoning has nothing to correct either with, so error should grow past NFR-1's
        // 15 m P95 bound by the end of the outage; visual re-localization must pull it back under.
        val s = session(dir, outage = outage, speedBiasFracBeforeOutage = 0.05, gyroZBias = 0.005f)
        val t0 = s.header.startedMs

        val visualPoints = Replayer(pack(), ReplayConfig(visual = true)).run(s, listOf(outage))
        val visualInOutage = visualPoints.filter { it.inOutage }
        val visualP95 = p95(visualInOutage.map { truthErrorM(it, t0) })

        val drPoints = Replayer(pack(), ReplayConfig(visual = false)).run(s, listOf(outage))
        val drLast = drPoints.last { it.inOutage }
        val drEndError = truthErrorM(drLast, t0)

        assertTrue(visualP95 <= 15.0, "visual=true P95=$visualP95 (NFR-1 must hold even with the injected bias)")
        assertTrue(
            drEndError > 15.0,
            "visual=false end-of-outage error=$drEndError should exceed 15 m " +
                "(bias too small to separate visual from dead-reckoning-only; increase it)",
        )
        println(
            "visualCorrectsBiasedDeadReckoningWhileDeadReckoningAloneExceedsNfr1: " +
                "visual P95=$visualP95 m, dead-reckoning end-of-outage error=$drEndError m",
        )
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

    @Test fun nonFiniteFieldsAreWrittenAsNullSoEveryLineStaysValidJson() {
        val dir = createTempDir()
        val header = SessionHeader(model = "m", refpackCreatedAt = "c", device = "d", startedMs = 1, mode = "gps")
        val s = SessionData(header, emptyList(), emptyList(), DescriptorLog.read(File(dir, "empty.desc").also {
            DescriptorLogWriter(it, 1).close()
        }))
        val out = File(dir, "traj.jsonl")
        val points = listOf(
            TrajPoint(1, Double.NaN, 37.6, 4.0, false, Float.NaN, null),
            TrajPoint(2, 55.75, Double.POSITIVE_INFINITY, Double.NaN, true, null, false),
        )
        TrajectoryWriter.write(out, s, ReplayConfig(), emptyList(), points)
        val lines = out.readLines()
        // Every line must parse as JSON (no bare NaN/Infinity tokens) and non-finite fields are null.
        assertEquals("{\"t_ms\":1,\"lat\":null,\"lon\":37.6,\"sigma_m\":4.0,\"outage\":false,\"vis_sim\":null,\"vis_ok\":null}", lines[1])
        assertEquals("{\"t_ms\":2,\"lat\":55.75,\"lon\":null,\"sigma_m\":null,\"outage\":true,\"vis_sim\":null,\"vis_ok\":false}", lines[2])
        for (line in lines) assertTrue(!line.contains("NaN") && !line.contains("Infinity"), line)
    }

    @Test fun zeroGnssAccuracyFieldsAreFlooredNotThrown() {
        val dir = createTempDir()
        val t0 = 1_700_000_000_000L
        val sensors = mutableListOf<SensorEvent>()
        val frames = mutableListOf<FrameRecord>()
        val descFile = File(dir, "z.desc")
        DescriptorLogWriter(descFile, dim).use { w ->
            for (step in 0..2000) { // 100 Гц × 20 с
                val tMs = t0 + step * 10.0
                // Vibration in the accelerometer, same as the main synthetic session, so the
                // StationaryDetector/YawRate path is exercised the same way as a real drive.
                sensors += AccelEvent(tMs, 0f, 0f, 9.81f + 0.3f * kotlin.math.sin(step.toFloat()))
                sensors += GyroEvent(tMs, 0f, 0f, 0f)
                val eMeters = step * 0.1
                if (step % 100 == 0) {
                    val ll = enu.toLatLon(eMeters, 0.0)
                    // spd_acc = 0 and brg_acc = 0: devices sometimes report zero accuracy.
                    sensors += LocEvent(tMs, ll[0], ll[1], 3f, 10f, 0f, 90f, 0f)
                }
                if (step % 50 == 0 && step >= 100) { // a couple of frames once the filter is initialized
                    val t = t0 + step * 10L
                    frames += FrameRecord(tMs = t, mode = "gps", gps = null, prior = null, top = emptyList(),
                        fix = null, latMs = LatencyJson(0.0, 0.0, 0.0))
                    w.write(t, oneHot(minOf(n, (eMeters / 10.0).toInt())))
                }
            }
        }
        val header = SessionHeader(model = "m", refpackCreatedAt = "c", device = "d", startedMs = t0, mode = "gps")
        val s = SessionData(header, frames, sensors.sortedBy { it.tMs }, DescriptorLog.read(descFile))
        // Must not throw Ekf2d's require(sigma > 0 && sigma.isFinite()).
        val points = Replayer(pack(), ReplayConfig()).run(s, emptyList())
        assertTrue(points.isNotEmpty())
        assertTrue(points.all { it.sigmaM.isFinite() }, "all sigmaM must be finite: ${points.map { it.sigmaM }}")
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("replay").toFile()
}
