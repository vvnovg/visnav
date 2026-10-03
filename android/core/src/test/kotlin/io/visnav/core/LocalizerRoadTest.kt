package io.visnav.core

import kotlin.math.abs
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

    private fun run(config: LocalizerConfig, roads: RoadPack?, biasRadS: Float = 0.005f): List<LocalizerOutput> {
        val localizer = Localizer(pack, config, roads)
        val out = ArrayList<LocalizerOutput>()
        for (step in 0..12_000) {
            val tMs = t0 + step * 10L
            val t = tMs.toDouble()
            val rel = tMs - t0
            val inGap = rel in 30_000 until 90_000
            localizer.onSensor(AccelEvent(t, 0f, 0f, 9.81f + 0.3f * sin(step.toFloat())))
            localizer.onSensor(GyroEvent(t, 0f, 0f, if (inGap) biasRadS else 0f))
            if (step % 100 == 0 && !inGap) {
                val ll = enu.toLatLon(step * 0.1, 0.0)
                localizer.onSensor(LocEvent(t, ll[0], ll[1], 3f, 10f, 0.3f, 90f, 2f))
            }
            if (step % 50 == 0) localizer.onFrame(tMs, null)?.let { out.add(it) }
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
}
