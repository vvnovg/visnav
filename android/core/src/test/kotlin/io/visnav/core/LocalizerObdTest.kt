package io.visnav.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Синтетическая прямая на восток: IMU 100 Гц (вибрация в движении, покой — без неё), GNSS и статус 1 Гц
 * (позиция σ = 3 м, скорость σ = 0.2 м/с), OBD 5 Гц со спидометром +3 % и шумом ±1 км/ч (округление до целого),
 * кадры без дескриптора 2 Гц (визуальная навигация выключена).
 */
class LocalizerObdTest {
    private val enu = Enu(55.75, 37.60)
    private val t0 = 1_700_000_000_000L

    private fun pack(): RefPack =
        RefPack(1, 1, doubleArrayOf(55.75), doubleArrayOf(37.60), FloatArray(1), ShortArray(1))

    private class Run(val outputs: List<Pair<LocalizerOutput, Double>>) // выход и истинная координата e, м

    private fun gauss(rnd: Random): Double {
        val u1 = rnd.nextDouble(1e-12, 1.0); val u2 = rnd.nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * Math.PI * u2)
    }

    /** Показание спидометра: +3 %, шум ±1 км/ч, целое. */
    private fun speedometer(v: Double, rnd: Random): Int = (1.03 * v * 3.6 + rnd.nextDouble(-1.0, 1.0)).roundToInt()

    private fun run(
        config: LocalizerConfig, durationS: Double, speed: (Double) -> Double,
        gnssOn: (Double) -> Boolean = { true }, obdKmh: ((Double, Double, Random) -> Int?)? = null,
        imuStill: (Double) -> Boolean = { false }, cn0: Float = 35f, seed: Int = 3,
    ): Run {
        val rnd = Random(seed)
        val localizer = Localizer(pack(), config.copy(visual = false))
        val out = ArrayList<Pair<LocalizerOutput, Double>>()
        var e = 0.0
        val steps = (durationS * 100).roundToInt()
        for (step in 0..steps) {
            val tMs = t0 + step * 10L
            val t = tMs.toDouble()
            val s = step / 100.0
            val v = speed(s)
            if (step > 0) e += v * 0.01
            val vib = if (imuStill(s)) 0f else 0.3f * sin(step.toFloat())
            localizer.onSensor(AccelEvent(t, 0f, 0f, 9.81f + vib))
            localizer.onSensor(GyroEvent(t, 0f, 0f, 0f))
            if (step % 100 == 0 && gnssOn(s)) {
                val ll = enu.toLatLon(e + 3.0 * gauss(rnd), 3.0 * gauss(rnd))
                val vg = maxOf(0.0, v + 0.2 * gauss(rnd))
                // Статус до фикса: здоровье на момент первого фикса уже учитывает C/N0.
                localizer.onSensor(GnssStatusEvent(t, 16, 14, cn0, 5f))
                localizer.onSensor(LocEvent(t, ll[0], ll[1], 3f, vg.toFloat(), 0.3f, if (vg >= 1.0) 90f else null, 2f))
            }
            if (obdKmh != null && step % 20 == 7) obdKmh(s, v, rnd)?.let { localizer.onSensor(ObdSpeedEvent(t, it, 40.0)) }
            if (step % 50 == 0) localizer.onFrame(tMs, null)?.let { out.add(it to e) }
        }
        return Run(out)
    }

    private fun errorM(o: LocalizerOutput, eTrue: Double): Double {
        val en = enu.toEn(o.lat, o.lon)
        return hypot(en[0] - eTrue, en[1])
    }

    /** 15 м/с; в пропадании (60–180 с) — замедление до 8 м/с на ~30 с: при постоянной скорости DR без OBD тоже точен. */
    private fun outageSpeed(s: Double): Double = when {
        s < 95.0 -> 15.0
        s < 98.5 -> 15.0 - 2.0 * (s - 95.0)
        s < 125.0 -> 8.0
        s < 128.5 -> 8.0 + 2.0 * (s - 125.0)
        else -> 15.0
    }

    private fun outageDriftPct(obd: ObdConfig): Pair<Double, Double> {
        val r = run(
            LocalizerConfig(obd = obd), 180.0, ::outageSpeed, gnssOn = { it < 60.0 },
            obdKmh = { _, v, rnd -> speedometer(v, rnd) },
        )
        val start = r.outputs.first { it.first.tMs >= t0 + 60_000 }.second
        val (last, eTrue) = r.outputs.last()
        val dist = eTrue - start
        return errorM(last, eTrue) / dist * 100 to dist
    }

    @Test fun obdSpeedKeepsOutageDriftBelowThreePercent() {
        val (withObd, dist) = outageDriftPct(ObdConfig())
        val (withoutObd, _) = outageDriftPct(ObdConfig(enabled = false))
        assertTrue(dist > 1_400.0, "outage distance $dist m")
        assertTrue(withObd <= 3.0, "drift with OBD $withObd % over $dist m")
        assertTrue(withoutObd > withObd, "drift without OBD $withoutObd % vs with $withObd %")
    }

    @Test fun learnsSpeedometerFactorFromHealthyGnss() {
        for (kInit in listOf(1.0 / 1.03, 1.0)) {
            val r = run(
                LocalizerConfig(obd = ObdConfig(kInit = kInit)), 300.0, { 15.0 },
                obdKmh = { _, v, rnd -> speedometer(v, rnd) },
            )
            val last = r.outputs.last().first
            assertEquals(GnssHealth.GOOD, last.health)
            val k = assertNotNull(last.obdK)
            assertTrue(k in 0.965..0.975, "kInit=$kInit k=$k")
        }
    }

    @Test fun doesNotLearnFactorWhileGnssDegraded() {
        val r = run(
            LocalizerConfig(obd = ObdConfig(kInit = 1.0)), 300.0, { 15.0 },
            obdKmh = { _, v, rnd -> speedometer(v, rnd) }, cn0 = 20f,
        )
        assertTrue(r.outputs.drop(10).all { it.first.health == GnssHealth.DEGRADED })
        assertTrue(r.outputs.mapNotNull { it.first.obdK }.all { it == 1.0 })
        assertEquals(1.0, r.outputs.last().first.obdK)
    }

    @Test fun zeroKmhWhileMovingIsIgnored() {
        // GNSS до 60 с, затем пропадание: P скорости растёт, и без отбрасывания ноль прошёл бы гейт.
        val r = run(
            LocalizerConfig(), 150.0, { 15.0 }, gnssOn = { it < 60.0 },
            obdKmh = { s, v, rnd -> if (s >= 60.0) 0 else speedometer(v, rnd) },
        )
        val window = r.outputs.filter { it.first.tMs >= t0 + 60_000 }
        assertTrue(window.size > 100)
        val minSpeed = window.minOf { it.first.speedMps }
        assertTrue(minSpeed > 13.0, "min filter speed $minSpeed")
    }

    @Test fun zeroKmhWhileStationaryStopsTheFilter() {
        val speed = { s: Double -> if (s < 60.0) 15.0 else if (s < 67.5) 15.0 - 2.0 * (s - 60.0) else 0.0 }
        val r = run(
            LocalizerConfig(), 100.0, speed, gnssOn = { it < 60.0 },
            obdKmh = { _, v, rnd -> if (v == 0.0) 0 else speedometer(v, rnd) }, imuStill = { it >= 67.5 },
        )
        val stopped = r.outputs.filter { it.first.tMs >= t0 + 75_000 }
        assertTrue(stopped.isNotEmpty())
        assertTrue(stopped.all { it.first.stationary })
        val maxSpeed = stopped.maxOf { abs(it.first.speedMps) }
        assertTrue(maxSpeed < 0.2, "max filter speed while stopped $maxSpeed")
    }

    @Test fun obdFactorAppearsOnlyAfterFirstObdEvent() {
        val r = run(
            LocalizerConfig(), 60.0, { 15.0 },
            obdKmh = { s, v, rnd -> if (s >= 30.0) speedometer(v, rnd) else null },
        )
        val before = r.outputs.filter { it.first.tMs < t0 + 30_000 }
        val after = r.outputs.filter { it.first.tMs >= t0 + 30_100 }
        assertTrue(before.isNotEmpty() && after.isNotEmpty())
        assertTrue(before.all { it.first.obdK == null })
        assertTrue(after.all { it.first.obdK != null })
        val off = run(
            LocalizerConfig(obd = ObdConfig(enabled = false)), 60.0, { 15.0 },
            obdKmh = { _, v, rnd -> speedometer(v, rnd) },
        )
        assertTrue(off.outputs.all { it.first.obdK == null })
    }

    @Test fun trajectoryRowCarriesObdFactorOnlyWhenPresent() {
        val base = LocalizerOutput(
            1L, 55.75, 37.6, 3.0, null, null, "off", false, NavMode.GNSS, GnssHealth.GOOD, emptySet(),
        )
        val without = TrajectoryFormat.row(base, false, null)
        assertFalse(without.contains("obd_k"))
        val with = TrajectoryFormat.row(base.copy(obdK = 0.971), false, null)
        assertTrue(with.endsWith(",\"obd_k\":0.971}"), with)
        val parsed = Json.parseToJsonElement(with).jsonObject
        assertEquals(0.971, parsed.getValue("obd_k").jsonPrimitive.double)
        assertNull(Json.parseToJsonElement(without).jsonObject["obd_k"])
    }
}
