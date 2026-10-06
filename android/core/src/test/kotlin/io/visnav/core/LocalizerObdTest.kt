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

    /** Выходы с истинной координатой e (м) и сам локализатор (для внутреннего состояния). */
    private class Run(val outputs: List<Pair<LocalizerOutput, Double>>, val localizer: Localizer)

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
        gnssSpeedFactor: (Double) -> Double = { 1.0 }, obdEverySteps: Int = 20, obdPhaseSteps: Int = 7,
        gnssEastOffset: (Double) -> Double = { 0.0 }, gnssSpeedOffset: (Double) -> Double = { 0.0 },
        onSecond: ((Double, Localizer) -> Unit)? = null,
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
                val ll = enu.toLatLon(e + gnssEastOffset(s) + 3.0 * gauss(rnd), 3.0 * gauss(rnd))
                val vg = maxOf(0.0, v + 0.2 * gauss(rnd)) * gnssSpeedFactor(s) + gnssSpeedOffset(s)
                localizer.onSensor(GnssStatusEvent(t, 16, 14, cn0, 5f))
                localizer.onSensor(LocEvent(t, ll[0], ll[1], 3f, vg.toFloat(), 0.3f, if (vg >= 1.0) 90f else null, 2f))
            }
            if (obdKmh != null && step % obdEverySteps == obdPhaseSteps) obdKmh(s, v, rnd)?.let { localizer.onSensor(ObdSpeedEvent(t, it, 40.0)) }
            if (step % 50 == 0) localizer.onFrame(tMs, null)?.let { out.add(it to e) }
            if (step % 100 == 0) onSecond?.invoke(s, localizer)
        }
        return Run(out, localizer)
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
        // В симуляции статус подаётся до фикса: иначе первый фикс оценивается до первого C/N0, считается GOOD,
        // и k один раз учится — тест проверял бы порядок событий, а не правило «учиться только при GOOD».
        val r = run(
            LocalizerConfig(obd = ObdConfig(kInit = 1.0)), 300.0, { 15.0 },
            obdKmh = { _, v, rnd -> speedometer(v, rnd) }, cn0 = 20f,
        )
        assertTrue(r.outputs.drop(10).all { it.first.health == GnssHealth.DEGRADED })
        assertTrue(r.outputs.mapNotNull { it.first.obdK }.all { it == 1.0 })
        assertEquals(1.0, r.outputs.last().first.obdK)
    }

    /** k в конце 300 с на 15 м/с при здоровом GNSS; OBD 5 Гц через 70 мс после каждого фикса. */
    private fun finalK(
        obdKmh: (Double, Double, Random) -> Int?, gnssSpeedFactor: (Double) -> Double = { 1.0 },
    ): Double = run(
        LocalizerConfig(), 300.0, { 15.0 }, obdKmh = obdKmh, gnssSpeedFactor = gnssSpeedFactor,
    ).outputs.last().first.obdK!!

    @Test fun singleZeroObdNextToFixDoesNotMoveFactor() {
        // Ноль через 70 мс после фикса 290 с: интерполяция 15 → 0 дала бы отношение ≫ 1, и k ушёл бы к kMax.
        val base = finalK({ _, v, rnd -> speedometer(v, rnd) })
        val glitch = finalK({ s, v, rnd -> speedometer(v, rnd).let { if (abs(s - 290.07) < 1e-6) 0 else it } })
        assertTrue(abs(glitch - base) < 1e-3, "k with zero glitch $glitch vs $base")
    }

    @Test fun gnssSpeedOutlierRatioIsIgnored() {
        // Фикс 290 с со скоростью ×1.5: отношение ≈ 1.46 вне [kMin − 0.05, kMax + 0.05] — выброс.
        val base = finalK({ _, v, rnd -> speedometer(v, rnd) })
        val outlier = finalK({ _, v, rnd -> speedometer(v, rnd) }, gnssSpeedFactor = { if (it == 290.0) 1.5 else 1.0 })
        assertTrue(abs(outlier - base) < 1e-3, "k with GNSS outlier $outlier vs $base")
    }

    @Test fun factorDoesNotLearnWhileMonitorAccumulatesSpeedMismatch() {
        // С 100 с каждые 30 с — 4 с скорости GNSS ×0.83 (≈ −2.55 м/с): правило (a) копит, но до 5 с не доходит,
        // окно (b) не включено, причины нет и здоровье GOOD. Отношение ≈ 0.81 — внутри окна выброса [0.80, 1.07]
        // (завышение GNSS на > 2 м/с дало бы отношение > 1.07 — выброс, и запрет не проверялся бы),
        // так что без запрета k потянулся бы к нему.
        val bursts = { s: Double -> if (s >= 100.0 && (s.toInt() - 100) % 30 < 4) 0.83 else 1.0 }
        val r = run(
            LocalizerConfig(), 300.0, { 15.0 }, obdKmh = { _, v, rnd -> speedometer(v, rnd) }, gnssSpeedFactor = bursts,
        )
        assertTrue(r.outputs.none { GnssReason.SPEED_MISMATCH in it.first.reasons })
        assertTrue(r.outputs.filter { it.first.tMs >= t0 + 10_000 }.all { it.first.health == GnssHealth.GOOD })
        val base = finalK({ _, v, rnd -> speedometer(v, rnd) })
        val k = r.outputs.last().first.obdK!!
        assertTrue(abs(k - base) < 2e-3, "k with suspicious bursts $k vs $base")
    }

    /** Спидометр с ошибкой [f] (1.08 = +8 %), шум ±1 км/ч, целое. */
    private fun speedometerF(f: Double): (Double, Double, Random) -> Int? =
        { _, v, rnd -> (f * v * 3.6 + rnd.nextDouble(-1.0, 1.0)).roundToInt() }

    /** Прогон с записью момента включения (b) и kSlow в этот момент. */
    private class ArmRun(val run: Run, val armedAtS: Double?, val kSlowAtArm: Double)

    private fun runArm(config: LocalizerConfig, durationS: Double, f: Double, seed: Int = 3): ArmRun {
        var armedAtS: Double? = null
        var kSlowAtArm = Double.NaN
        val r = run(config, durationS, { 15.0 }, obdKmh = speedometerF(f), seed = seed, onSecond = { s, loc ->
            if (armedAtS == null && loc.obdDistanceArmed) { armedAtS = s; kSlowAtArm = loc.obdKSlow!! }
        })
        return ArmRun(r, armedAtS, kSlowAtArm)
    }

    @Test fun overreadingSpeedometerLearnsBeforeDistanceCheckArms() {
        // +8 %: дальше от kInit = 1/1.03, чем порог (b) в 5 %. В момент включения kSlow := k.
        val a = runArm(LocalizerConfig(), 900.0, 1.08)
        assertTrue(a.run.outputs.none { GnssReason.SPEED_MISMATCH in it.first.reasons })
        val armed = assertNotNull(a.armedAtS, "distance check never armed")
        assertTrue(armed >= 600.0, "armed at $armed s")
        assertTrue(a.run.localizer.obdLearnedMs >= 600_000.0)
        assertEquals(1 / 1.08, a.kSlowAtArm, 0.01 / 1.08)
    }

    @Test fun overreadingSpeedometerStillCatchesDriftSpoofAfterArming() {
        val from = 720.0
        val r = run(
            LocalizerConfig(), 900.0, { 15.0 }, obdKmh = speedometerF(1.08),
            gnssEastOffset = { if (it >= from) it - from else 0.0 }, gnssSpeedOffset = { if (it >= from) 1.0 else 0.0 },
        )
        assertTrue(r.outputs.none { it.first.tMs < t0 + from * 1000 && GnssReason.SPEED_MISMATCH in it.first.reasons })
        val first = r.outputs.firstOrNull { it.first.tMs >= t0 + from * 1000 && GnssReason.SPEED_MISMATCH in it.first.reasons }
        assertNotNull(first, "SPEED_MISMATCH never raised")
        assertTrue(first.first.tMs - (t0 + from * 1000) <= 40_000, "after ${first.first.tMs - t0 - from * 1000} ms")
    }

    @Test fun restoredFactorsAreInitialButDoNotArmImmediately() {
        val loc = Localizer(pack(), LocalizerConfig(obd = ObdConfig(kRestore = 0.93, kSlowRestore = 1.5)))
        assertFalse(loc.obdDistanceArmed) // сначала проверка сохранения
        loc.onSensor(ObdSpeedEvent(t0.toDouble(), 50, 40.0))
        assertEquals(1.02, loc.obdKSlow) // в пределах [kMin, kMax]
        assertEquals(0.0, loc.obdLearnedMs)
        val r = run(LocalizerConfig(obd = ObdConfig(kRestore = 0.93)), 1.0, { 15.0 }, obdKmh = { _, v, rnd -> speedometer(v, rnd) })
        assertEquals(0.93, r.outputs.last().first.obdK!!, 3e-3) // не больше пары шагов обучения
        assertFalse(r.localizer.obdDistanceArmed)
    }

    @Test fun strongOverreadingDoesNotLatchInnovationBeforeFactorConverges() {
        // +10 % и +12 % от kInit = 1/1.03: без σ_k OBD (σ = 0.3 м/с) уводил фильтр от фиксов, INNOVATION
        // фиксировался раньше схождения k, а при нём k не учится.
        for (f in listOf(1.10, 1.12)) {
            val a = runArm(LocalizerConfig(), 900.0, f)
            assertTrue(a.run.outputs.none { GnssReason.INNOVATION in it.first.reasons }, "INNOVATION at +${f - 1}")
            assertTrue(a.run.outputs.none { GnssReason.SPEED_MISMATCH in it.first.reasons }, "SPEED_MISMATCH at +${f - 1}")
            val armed = assertNotNull(a.armedAtS, "never armed at +${f - 1}")
            assertTrue(armed >= 600.0)
            assertEquals(1 / f, a.run.outputs.last().first.obdK!!, 0.01 / f, "k at +${f - 1}")
        }
    }

    @Test fun underreadingSpeedometerIsClampedAtKMax() {
        // −5 % (k было бы 1.053): по ECE R39 так не бывает, k упирается в kMax = 1.02.
        val r = run(LocalizerConfig(), 300.0, { 15.0 }, obdKmh = speedometerF(0.95))
        assertEquals(1.02, r.outputs.last().first.obdK!!, 1e-9)
    }

    @Test fun confirmedRestoreArmsAfterCheckPeriod() {
        for (seed in 1..8) { // допуск 1.5 % выдерживает шум GNSS и OBD
            val a = runArm(LocalizerConfig(obd = ObdConfig(kSlowRestore = 1 / 1.03)), 200.0, 1.03, seed)
            val armed = assertNotNull(a.armedAtS, "distance check never armed, seed $seed")
            assertTrue(armed >= 120.0 && armed <= 125.0, "armed at $armed s, seed $seed")
            assertEquals(1 / 1.03, a.kSlowAtArm, 1e-12) // восстановленная kSlow сохраняется
            assertTrue(a.run.outputs.none {
                GnssReason.SPEED_MISMATCH in it.first.reasons || GnssReason.SPEED_FACTOR_CHANGED in it.first.reasons
            })
            assertTrue(abs(assertNotNull(a.run.localizer.obdRestoreDeltaPct)) < 1.0, "seed $seed")
        }
    }

    @Test fun wrongRestoreIsDiscardedAndCarIsNotLockedUntrusted() {
        // Сохранено 1/1.03, а спидометр теперь +10 % (другие шины): за 120 с k уходит дальше 1.5 % — сохранение
        // отбрасывается (SPEED_FACTOR_CHANGED, DEGRADED), k учится дальше, (b) включается после обычного прогрева → GOOD.
        val a = runArm(LocalizerConfig(obd = ObdConfig(kSlowRestore = 1 / 1.03)), 900.0, 1.10)
        assertTrue(a.run.outputs.none { GnssReason.SPEED_MISMATCH in it.first.reasons })
        val warmUp = a.run.outputs.filter { it.first.tMs in (t0 + 130_000)..(t0 + 590_000) }
        assertTrue(warmUp.isNotEmpty() && warmUp.all {
            it.first.health == GnssHealth.DEGRADED && it.first.reasons == setOf(GnssReason.SPEED_FACTOR_CHANGED)
        })
        assertTrue(a.run.outputs.filter { it.first.tMs < t0 + 115_000 }.none { GnssReason.SPEED_FACTOR_CHANGED in it.first.reasons })
        val delta = assertNotNull(a.run.localizer.obdRestoreDeltaPct)
        assertTrue(delta < -3.0, "delta=$delta %")
        val armed = assertNotNull(a.armedAtS, "distance check never armed")
        assertTrue(armed >= 600.0, "armed at $armed s")
        assertEquals(1 / 1.10, a.kSlowAtArm, 0.01 / 1.10)
        assertTrue(a.run.outputs.none { GnssReason.INNOVATION in it.first.reasons })
        assertTrue(a.run.outputs.filter { it.first.tMs >= t0 + (armed + 1) * 1000 }.all { it.first.health == GnssHealth.GOOD })
    }

    @Test fun monitorUsesFastFactorBeforeArmingAndSlowFactorAfter() {
        val before = Localizer(pack(), LocalizerConfig(obd = ObdConfig(kRestore = 0.95, kSlowRestore = 1.05)))
        before.onSensor(ObdSpeedEvent(t0.toDouble(), 54, 40.0))
        assertEquals(0.95 * 15.0, before.monitorObdMps, 1e-9)
        // Сохранено kSlow = k = 1.0, спидометр +1 %: проверка проходит (|k − 1| ≈ 0.9 % < 1.5 %), дальше k → 0.99,
        // а kSlow почти стоит на 1.0.
        val r = run(
            LocalizerConfig(obd = ObdConfig(kRestore = 1.0, kSlowRestore = 1.0)), 300.0, { 15.0 }, obdKmh = speedometerF(1.01),
        )
        assertTrue(r.localizer.obdDistanceArmed)
        r.localizer.onSensor(ObdSpeedEvent(t0 + 300_500.0, 54, 40.0))
        val kSlow = r.localizer.obdKSlow!!
        val k = r.outputs.last().first.obdK!!
        assertTrue(kSlow - k > 0.005, "kSlow=$kSlow k=$k")
        assertEquals(kSlow * 15.0, r.localizer.monitorObdMps, 1e-9)
    }

    @Test fun staleObdDoesNotTeachFactor() {
        // Фиксы раз в 2 с (чётные секунды), OBD раз в 2 с на нечётных: до ближайшего OBD 1000 мс > 500 мс.
        val r = run(
            LocalizerConfig(obd = ObdConfig(kInit = 1.0)), 300.0, { 15.0 }, gnssOn = { it.roundToInt() % 2 == 0 },
            obdKmh = { _, v, rnd -> speedometer(v, rnd) }, obdEverySteps = 200, obdPhaseSteps = 100,
        )
        assertEquals(GnssHealth.GOOD, r.outputs.last().first.health)
        assertEquals(1.0, r.outputs.last().first.obdK)
    }

    @Test fun slowFactorLagsFastFactor() {
        // Сохранено kSlow = 0.98 при kInit = 1.0: проверка проходит (k ≈ 0.975 к 120 с), дальше kSlow учится медленно.
        val kSlow0 = 0.98
        val r = run(
            LocalizerConfig(obd = ObdConfig(kInit = 1.0, kSlowRestore = kSlow0)), 300.0, { 15.0 },
            obdKmh = { _, v, rnd -> speedometer(v, rnd) },
        )
        assertTrue(r.localizer.obdDistanceArmed)
        val k = r.outputs.last().first.obdK!!
        val kSlow = assertNotNull(r.localizer.obdKSlow)
        assertTrue(kSlow < kSlow0, "kSlow=$kSlow must move toward 1/1.03")
        assertTrue(kSlow0 - kSlow < 0.3 * (kSlow0 - k), "kSlow=$kSlow k=$k")
    }

    @Test fun slowFactorDoesNotLearnBeforeArming() {
        val r = run(
            LocalizerConfig(obd = ObdConfig(kInit = 1.0)), 300.0, { 15.0 },
            obdKmh = { _, v, rnd -> speedometer(v, rnd) },
        )
        assertFalse(r.localizer.obdDistanceArmed)
        assertEquals(1.0, r.localizer.obdKSlow) // kSlow := k только в момент включения (b)
        assertTrue(r.outputs.last().first.obdK!! < 0.98)
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
