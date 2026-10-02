package io.visnav.replay

import io.visnav.core.AccelEvent
import io.visnav.core.AgcEvent
import io.visnav.core.DescriptorLog
import io.visnav.core.DescriptorLogWriter
import io.visnav.core.Enu
import io.visnav.core.FrameRecord
import io.visnav.core.Geo
import io.visnav.core.GnssStatusEvent
import io.visnav.core.GyroEvent
import io.visnav.core.Half
import io.visnav.core.LatencyJson
import io.visnav.core.LocEvent
import io.visnav.core.RefPack
import io.visnav.core.SensorEvent
import io.visnav.core.SessionHeader
import java.io.File
import kotlin.math.sin

/**
 * Синтетическая сессия: 10 м/с на восток 120 с, эталон каждые 10 м с дескриптором-«отпечатком»,
 * IMU 100 Гц с вибрацией, GNSS, статус GNSS и АРУ 1 Гц, кадры 2 Гц с дескриптором ближайшего эталона.
 */
object SyntheticSession {
    val enu = Enu(55.75, 37.60)
    const val T0 = 1_700_000_000_000L
    private const val N = 120
    private const val DIM = N + 1

    private fun oneHot(i: Int) = FloatArray(DIM).also { it[i] = 1f }

    fun pack(): RefPack {
        val count = N + 1
        val lats = DoubleArray(count); val lons = DoubleArray(count)
        val desc = ShortArray(count * DIM)
        for (i in 0 until count) {
            val ll = enu.toLatLon(i * 10.0, 0.0); lats[i] = ll[0]; lons[i] = ll[1]
            desc[i * DIM + i] = Half.fromFloat(1f)
        }
        return RefPack(count, DIM, lats, lons, FloatArray(count), desc)
    }

    /**
     * Статус GNSS в окне [uniformFromMs, uniformToMs) (мс от начала) «как у спуфера»:
     * used = 10, cn0Std = [uniformCn0Std]; вне окна — used = 14, cn0Std = 5.
     */
    fun session(
        dir: File, uniformFromMs: Long = -1, uniformToMs: Long = -1, uniformCn0Std: Float = 1f, accM: Float = 3f,
    ): SessionData {
        val sensors = mutableListOf<SensorEvent>()
        val frames = mutableListOf<FrameRecord>()
        val descFile = File(dir, "s.desc")
        DescriptorLogWriter(descFile, DIM).use { w ->
            for (step in 0..12_000) {
                val tMs = T0 + step * 10L
                val t = tMs.toDouble()
                val rel = tMs - T0
                sensors += AccelEvent(t, 0f, 0f, 9.81f + 0.3f * sin(step.toFloat()))
                sensors += GyroEvent(t, 0f, 0f, 0f)
                val eMeters = step * 0.1
                if (step % 100 == 0) {
                    val ll = enu.toLatLon(eMeters, 0.0)
                    sensors += LocEvent(t, ll[0], ll[1], accM, 10f, 0.3f, 90f, 2f)
                    val uniform = rel >= uniformFromMs && rel < uniformToMs
                    sensors += if (uniform) GnssStatusEvent(t, 16, 10, 35f, uniformCn0Std)
                    else GnssStatusEvent(t, 16, 14, 35f, 5f)
                    sensors += AgcEvent(t, 40f, 10)
                }
                if (step % 50 == 0) {
                    frames += FrameRecord(tMs = tMs, mode = "gps", gps = null, prior = null, top = emptyList(),
                        fix = null, latMs = LatencyJson(0.0, 0.0, 0.0))
                    w.write(tMs, oneHot(minOf(N, (eMeters / 10.0).toInt())))
                }
            }
        }
        val header = SessionHeader(model = "m", refpackCreatedAt = "c", device = "d", startedMs = T0, mode = "gps")
        return SessionData(header, frames, sensors.sortedBy { it.tMs }, DescriptorLog.read(descFile))
    }

    fun truthErrorM(p: TrajPoint): Double {
        val ll = enu.toLatLon((p.tMs - T0) / 1000.0 * 10.0, 0.0)
        return Geo.haversineM(ll[0], ll[1], p.lat, p.lon)
    }
}
