package io.visnav.core

import kotlin.math.sqrt

/**
 * Скорость поворота курса из гироскопа, не зависящая от ориентации телефона в держателе:
 * проекция угловой скорости на вертикаль. Вертикаль — сглаженное ускорение (неподвижный
 * акселерометр показывает +g вверх). Поворот против часовой вокруг «вверх» уменьшает курс.
 */
class YawRate(private val alpha: Double = 0.02) {
    private val g = DoubleArray(3)
    private var hasG = false

    fun onAccel(x: Float, y: Float, z: Float) {
        if (!hasG) { g[0] = x.toDouble(); g[1] = y.toDouble(); g[2] = z.toDouble(); hasG = true; return }
        g[0] += alpha * (x - g[0]); g[1] += alpha * (y - g[1]); g[2] += alpha * (z - g[2])
    }

    fun headingRate(wx: Float, wy: Float, wz: Float): Double? {
        if (!hasG) return null
        val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (norm < 1e-6) return null
        return -(wx * g[0] + wy * g[1] + wz * g[2]) / norm
    }
}

/** Машина стоит, если за последнее окно модуль ускорения почти не дрожит и гироскоп почти молчит. */
class StationaryDetector(
    private val windowMs: Double = 1000.0,
    private val accelStdMax: Double = 0.08,
    private val gyroMeanMax: Double = 0.01,
    private val minSamples: Int = 20,
) {
    private val accel = ArrayDeque<Pair<Double, Double>>()
    private val gyro = ArrayDeque<Pair<Double, Double>>()

    fun onAccel(tMs: Double, x: Float, y: Float, z: Float) { accel.addLast(tMs to norm(x, y, z)) }
    fun onGyro(tMs: Double, x: Float, y: Float, z: Float) { gyro.addLast(tMs to norm(x, y, z)) }

    fun isStationary(tMs: Double): Boolean {
        val from = tMs - windowMs
        while (accel.isNotEmpty() && accel.first().first < from) accel.removeFirst()
        while (gyro.isNotEmpty() && gyro.first().first < from) gyro.removeFirst()
        if (accel.size < minSamples || gyro.size < minSamples) return false
        val mean = accel.sumOf { it.second } / accel.size
        val std = sqrt(accel.sumOf { (it.second - mean) * (it.second - mean) } / accel.size)
        val gyroMean = gyro.sumOf { it.second } / gyro.size
        return std < accelStdMax && gyroMean < gyroMeanMax
    }

    private fun norm(x: Float, y: Float, z: Float) = sqrt((x * x + y * y + z * z).toDouble())
}
