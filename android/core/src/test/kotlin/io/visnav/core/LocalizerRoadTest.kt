package io.visnav.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 10 м/с на восток 120 с; GNSS 1 Гц вне окна [30 с, 90 с); в окне гироскоп смещён на biasRadS, курс
 * счисления уплывает. Кадры 2 Гц без дескрипторов (visual = false). Дорога way 1 — по треку (n = 0),
 * way 2 — параллельно в 25 м к северу.
 */
class LocalizerRoadTest {
    private val enu = Enu(55.75, 37.60)
    private val t0 = 1_700_000_000_000L
    private val pack = RefPack(1, 1, doubleArrayOf(55.75), doubleArrayOf(37.60), floatArrayOf(0f), shortArrayOf(0))

    private fun roads(): RoadPack {
        val a = straightRoad(-200.0, 0.0, 1500.0, 0.0, 100.0, 1, 0)
        val b = straightRoad(-200.0, 25.0, 1500.0, 25.0, 100.0, 2, a.first.size)
        return roadPackOf(enu, a.first + b.first, a.second + b.second)
    }

    private fun run(
        config: LocalizerConfig, roads: RoadPack?, biasRadS: Float = 0.005f, speed: Double = 10.0,
        onEnd: (Localizer) -> Unit = {}, onFrame: (Localizer, LocalizerOutput) -> Unit = { _, _ -> },
        lastStep: Int = 12_000, gnssStatus: Boolean = false,
    ): List<LocalizerOutput> {
        val localizer = Localizer(pack, config, roads)
        val out = ArrayList<LocalizerOutput>()
        for (step in 0..lastStep) {
            val tMs = t0 + step * 10L
            val t = tMs.toDouble()
            val rel = tMs - t0
            val inGap = rel in 30_000 until 90_000
            localizer.onSensor(AccelEvent(t, 0f, 0f, 9.81f + 0.3f * sin(step.toFloat())))
            localizer.onSensor(GyroEvent(t, 0f, 0f, if (inGap) biasRadS else 0f))
            if (step % 100 == 0 && !inGap) {
                if (gnssStatus) localizer.onSensor(GnssStatusEvent(t, 16, 14, 35f, 5f)) // обычный, не подменный
                val ll = enu.toLatLon(step * 0.01 * speed, 0.0)
                val fixSpeed = (if (step == 0) maxOf(speed, 3.0) else speed).toFloat() // инициализация требует >= 3 м/с
                localizer.onSensor(LocEvent(t, ll[0], ll[1], 3f, fixSpeed, 0.3f, 90f, 2f))
            }
            if (step % 50 == 0) localizer.onFrame(tMs, null)?.let { out.add(it); onFrame(localizer, it) }
            if (step == 8_999) onEnd(localizer)
        }
        return out
    }

    private fun inGap(o: LocalizerOutput) = (o.tMs - t0) in 33_000 until 90_000
    private fun lateralErrM(o: LocalizerOutput) = abs(enu.toEn(o.lat, o.lon)[1])

    @Test fun roadConstraintBoundsDeadReckoningDrift() {
        val with = run(LocalizerConfig(visual = false), roads()).filter(::inGap)
        val without = run(LocalizerConfig(visual = false), null).filter(::inGap)
        val maxWith = with.maxOf(::lateralErrM)
        val maxWithout = without.maxOf(::lateralErrM)
        assertTrue(maxWithout > 30.0, "drift without roads must exist: $maxWithout")
        assertTrue(maxWith <= 10.0, "with roads: $maxWith")
        assertTrue(with.count { it.road?.wayId == 1L } >= with.size * 98 / 100)
        assertTrue(with.count { it.road?.used == true } >= with.size * 80 / 100)
    }

    @Test fun constraintNotUsedWhileGnssIsFused() {
        val before = run(LocalizerConfig(visual = false), roads()).filter { (it.tMs - t0) < 30_000 }
        assertTrue(before.isNotEmpty() && before.all { it.road != null && !it.road!!.used })
    }

    @Test fun noRoadsMeansNoRoadInfo() {
        assertTrue(run(LocalizerConfig(visual = false), null).all { it.road == null })
    }

    @Test fun constraintOffStillReportsMatch() {
        val gap = run(LocalizerConfig(visual = false, roadConstraint = false), roads()).filter(::inGap)
        assertTrue(gap.all { it.road != null && !it.road!!.used })
        assertTrue(gap.maxOf(::lateralErrM) > 30.0)
    }

    private fun offsetRoads(): RoadPack {
        val a = straightRoad(-200.0, 8.0, 1500.0, 8.0, 100.0, 1, 0)
        return roadPackOf(enu, a.first, a.second)
    }

    @Test fun offsetCenterlineDoesNotBlockGnssReturn() {
        val out = run(LocalizerConfig(visual = false), offsetRoads())
        val gap = out.filter(::inGap)
        val maxGap = gap.maxOf(::lateralErrM)
        val at105 = out.first { it.tMs - t0 >= 105_000 }
        assertTrue(gap.all { it.road?.used == true }, "constraint used in gap")
        assertTrue(maxGap <= 12.0, "gap error: $maxGap")
        assertTrue(lateralErrM(at105) <= 4.0, "error at 105 s: ${lateralErrM(at105)}")
        assertTrue(at105.road?.used == false, "GNSS fused again at 105 s")
    }

    @Test fun lateralVarianceNeverCollapsesBelowFloor() {
        val floor = 0.5 * RoadClass.lateralSigmaM(RoadClass.RESIDENTIAL) - 0.1
        var minAcross = Double.MAX_VALUE
        val gap = run(LocalizerConfig(visual = false), roads(), onFrame = { l, o ->
            // с 38 с дисперсия выросла до границы; раньше она ниже границы из-за слитого GNSS, а не дороги
            if (o.tMs - t0 in 38_000 until 90_000) minAcross = minOf(minAcross, l.lateralSigmaAcross(Math.PI / 2))
        }).filter(::inGap)
        assertTrue(gap.isNotEmpty() && minAcross >= floor, "min lateral sigma $minAcross < $floor")
    }

    @Test fun noHeadingConstraintBelowHeadingSpeed() {
        var slow = 0.0; var fast = 0.0
        val cfg = LocalizerConfig(visual = false)
        val slowOut = run(cfg, roads(), speed = 2.5, onEnd = { slow = Math.toDegrees(it.headingSigmaRad) })
        run(cfg, roads(), speed = 10.0, onEnd = { fast = Math.toDegrees(it.headingSigmaRad) })
        assertTrue(slowOut.filter(::inGap).any { it.road?.used == true }, "road used at 2.5 m/s")
        assertTrue(fast <= 5.0 + 1.0, "heading constrained at speed: $fast")
        assertTrue(slow > 2 * fast, "heading not constrained when slow: $slow vs $fast")
    }

    private val arcR = 300.0
    private fun arcPos(phi: Double) = doubleArrayOf(arcR * sin(phi), arcR * (cos(phi) - 1))

    /** Дуга радиуса 300 м по часовой, узлы через 25 м; стартует на 0.1 рад раньше начала движения. */
    private fun arcRoads(): RoadPack {
        val dPhi = 25.0 / arcR
        val k = ((4.3 + 0.1) / dPhi).toInt()
        val nodes = (0..k).map { val q = arcPos(-0.1 + it * dPhi); Pair(q[0], q[1]) }
        return roadPackOf(enu, nodes, (0 until k).map { EdgeSpec(it, it + 1, 1L) })
    }

    /** Пары: расстояние до истинной точки и расстояние до истинной дуги (поперечная ошибка). */
    private fun runArc(config: LocalizerConfig, roads: RoadPack?): Pair<List<Double>, List<Double>> {
        val localizer = Localizer(pack, config, roads)
        val errs = ArrayList<Double>()
        val cross = ArrayList<Double>()
        val v = 10.0; val omega = v / arcR
        for (step in 0..12_000) {
            val tMs = t0 + step * 10L
            val t = tMs.toDouble()
            val rel = tMs - t0
            val inGap = rel in 30_000 until 90_000
            val phi = omega * step * 0.01
            // headingRate = −gz, поэтому истинному вращению по часовой соответствует gz = −ω
            val gz = (-omega + if (inGap) 0.005 else 0.0).toFloat()
            localizer.onSensor(AccelEvent(t, 0f, 0f, 9.81f + 0.3f * sin(step.toFloat())))
            localizer.onSensor(GyroEvent(t, 0f, 0f, gz))
            if (step % 100 == 0 && !inGap) {
                val q = arcPos(phi); val ll = enu.toLatLon(q[0], q[1])
                val brg = Math.toDegrees(PI / 2 + phi).toFloat()
                localizer.onSensor(LocEvent(t, ll[0], ll[1], 3f, v.toFloat(), 0.3f, brg, 2f))
            }
            if (step % 50 == 0) {
                val o = localizer.onFrame(tMs, null)
                if (o != null && rel in 33_000 until 90_000) {
                    val q = arcPos(phi); val en = enu.toEn(o.lat, o.lon)
                    errs.add(hypot(en[0] - q[0], en[1] - q[1]))
                    cross.add(abs(hypot(en[0], en[1] + arcR) - arcR))
                }
            }
        }
        return errs to cross
    }

    /**
     * Дуга с дрейфом гироскопа в пропуске GNSS: полная ошибка позиции (до истинной точки, включая вдоль-дорожную
     * составляющую) с дорогой остаётся ≤ 12 м, без дороги — > 30 м.
     */
    @Test
    fun curvedRoadGapStaysOnRoad() {
        val (with, _) = runArc(LocalizerConfig(visual = false), arcRoads())
        val (without, _) = runArc(LocalizerConfig(visual = false), null)
        assertTrue(without.max() > 30.0, "drift without roads must exist: ${without.max()}")
        assertTrue(with.max() <= 12.0, "with roads: ${with.max()} (without: ${without.max()})")
    }

    @Test fun curvedRoadGapKeepsCrossTrackError() {
        val (_, with) = runArc(LocalizerConfig(visual = false), arcRoads())
        val (_, without) = runArc(LocalizerConfig(visual = false), null)
        assertTrue(without.max() > 30.0, "drift without roads must exist: ${without.max()}")
        assertTrue(with.max() <= 12.0, "with roads: ${with.max()} (without: ${without.max()})")
    }

    /**
     * Истинной дороги нет в OSM, единственная дорога way 2 идёт параллельно в x м к северу. В пропуске GNSS
     * дорога утягивает фильтр на себя; вернувшийся GNSS (обычный статус спутников) должен вывести фильтр
     * обратно («выход по GPS»), а не объявляться подменой (INNOVATION).
     */
    private fun missingTrueRoad(x: Double) {
        val b = straightRoad(-200.0, x, 2000.0, x, 25.0, 2, 0)
        val out = run(
            LocalizerConfig(visual = false), roadPackOf(enu, b.first, b.second), lastStep = 15_000, gnssStatus = true,
        )
        fun errM(o: LocalizerOutput): Double {
            val en = enu.toEn(o.lat, o.lon)
            return hypot(en[0] - (o.tMs - t0) / 1000.0 * 10.0, en[1])
        }
        val gapUsed = out.filter(::inGap).count { it.road?.used == true }
        val after = out.filter { it.tMs - t0 >= 90_000 }
        val innov = after.filter { it.health == GnssHealth.UNTRUSTED && GnssReason.INNOVATION in it.reasons }
        val late = out.filter { it.tMs - t0 >= 115_000 }
        val maxLate = late.maxOf(::errM)
        val maxGapErr = out.filter(::inGap).maxOf(::errM)
        val gnssBack = after.firstOrNull { it.mode == NavMode.GNSS }?.let { (it.tMs - t0) / 1000.0 }
        val lastBad = after.lastOrNull { errM(it) > 5.0 }?.let { (it.tMs - t0) / 1000.0 }
        val m = "x=$x gapUsed=$gapUsed maxGapErr=$maxGapErr maxErrFrom115=$maxLate gnssBackAt=$gnssBack " +
            "lastErrOver5mAt=$lastBad innovationRows=${innov.size}"
        assertTrue(gapUsed > 0, "road used in gap: $m")
        assertTrue(innov.isEmpty(), "INNOVATION after 90 s: $m")
        assertTrue(late.isNotEmpty() && maxLate <= 5.0, "error from 115 s: $m")
    }

    @Test fun missingTrueRoadParallelAt18m() = missingTrueRoad(18.0)
    @Test fun missingTrueRoadParallelAt25m() = missingTrueRoad(25.0)
    @Test fun missingTrueRoadParallelAt40m() = missingTrueRoad(40.0)
}
