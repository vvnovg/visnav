package io.visnav.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Синтетическая поездка с шагом 10 мс: телефон лежит горизонтально (вертикаль = +z), поэтому
 * измеренный гироскоп z = −(скорость поворота по часовой) + смещение + шум. GNSS раз в 1 с,
 * визуальные фиксации раз в 0.5 с (σ = 8 м, 5 % выбросов на 300 м).
 */
class DriveSim(seed: Long) {
    data class Segment(val durationS: Double, val speed: Double, val turnRate: Double)
    data class Sample(
        val tS: Double, val e: Double, val n: Double, val psi: Double, val v: Double,
        val gyroZ: Double, val gnss: DoubleArray?, val vis: DoubleArray?,
    )

    private val rnd = Random(seed)
    private fun gauss(): Double { // Бокс — Мюллер
        val u1 = rnd.nextDouble(1e-12, 1.0); val u2 = rnd.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }

    fun run(
        segments: List<Segment>, startPsi: Double = Math.PI / 2, gyroBias: Double = 0.01, gyroNoise: Double = 0.01,
        gnssSigma: Double = 3.0, visSigma: Double = 8.0, outlierRate: Double = 0.05,
    ): List<Sample> {
        val dt = 0.01
        var e = 0.0; var n = 0.0; var psi = startPsi; var t = 0.0
        val out = ArrayList<Sample>()
        var step = 0
        for (seg in segments) {
            val steps = (seg.durationS / dt).toInt()
            repeat(steps) {
                e += seg.speed * sin(psi) * dt
                n += seg.speed * cos(psi) * dt
                psi = wrapAngle(psi + seg.turnRate * dt)
                t += dt; step++
                val gyroZ = -(seg.turnRate) + gyroBias + gyroNoise * gauss()
                val gnss = if (step % 100 == 0) doubleArrayOf(
                    e + gnssSigma * gauss(), n + gnssSigma * gauss(), seg.speed + 0.2 * gauss(),
                    wrapAngle(psi + Math.toRadians(2.0) * gauss()),
                ) else null
                val vis = if (step % 50 == 0) {
                    if (rnd.nextDouble() < outlierRate) doubleArrayOf(e + 300.0, n)
                    else doubleArrayOf(e + visSigma * gauss(), n + visSigma * gauss())
                } else null
                out.add(Sample(t, e, n, psi, seg.speed, gyroZ, gnss, vis))
            }
        }
        return out
    }
}
