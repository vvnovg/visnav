package io.visnav.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Угол в (−π, π]. */
fun wrapAngle(a: Double): Double {
    var r = a % (2 * PI)
    if (r <= -PI) r += 2 * PI
    if (r > PI) r -= 2 * PI
    return r
}

data class FilterConfig(
    val accelNoise: Double = 1.0,      // м/с^1.5 (√СПМ) — насколько быстро может меняться скорость
    val gyroNoise: Double = 0.01,      // рад/с — шум скорости поворота
    val gyroBiasWalk: Double = 1e-4,   // рад/с/√с — дрейф смещения гироскопа
    val posNoise: Double = 0.1,        // м/√с — немоделируемые боковые смещения
    val gateChi2Pos: Double = 13.8,    // χ², 2 степени свободы, 99.9 %
    val gateChi2Scalar: Double = 10.8, // χ², 1 степень свободы, 99.9 %
    val initGyroBiasSigma: Double = 0.01, // рад/с — начальная неопределённость смещения гироскопа
)

/**
 * Расширенный фильтр Калмана на плоскости. Состояние x = [e, n, ψ, v, b_g]:
 * позиция (м), курс (рад, от севера по часовой), скорость (м/с), смещение гироскопа (рад/с).
 * Модель: e' = v·sin ψ, n' = v·cos ψ, ψ' = ω − b_g, v и b_g — случайное блуждание.
 */
class Ekf2d(val config: FilterConfig = FilterConfig()) {
    val x = DoubleArray(N)
    val p = DoubleArray(N * N)
    var initialized = false
        private set

    fun init(e: Double, n: Double, psi: Double, v: Double, posSigma: Double, psiSigma: Double, vSigma: Double) {
        x[0] = e; x[1] = n; x[2] = wrapAngle(psi); x[3] = v; x[4] = 0.0
        p.fill(0.0)
        p[idx(0, 0)] = posSigma * posSigma
        p[idx(1, 1)] = posSigma * posSigma
        p[idx(2, 2)] = psiSigma * psiSigma
        p[idx(3, 3)] = vSigma * vSigma
        p[idx(4, 4)] = config.initGyroBiasSigma * config.initGyroBiasSigma
        initialized = true
    }

    fun predict(dt: Double, omega: Double) {
        check(initialized) { "filter not initialized" }
        require(dt >= 0) { "dt must be >= 0" }
        if (dt == 0.0) return
        val psi = x[2]; val v = x[3]
        val s = sin(psi); val c = cos(psi)
        x[0] += v * s * dt
        x[1] += v * c * dt
        x[2] = wrapAngle(psi + (omega - x[4]) * dt)

        val f = identity()
        f[idx(0, 2)] = v * c * dt; f[idx(0, 3)] = s * dt
        f[idx(1, 2)] = -v * s * dt; f[idx(1, 3)] = c * dt
        f[idx(2, 4)] = -dt
        val fp = mul(f, p)
        val next = mulT(fp, f)
        val q = config
        next[idx(0, 0)] += q.posNoise * q.posNoise * dt
        next[idx(1, 1)] += q.posNoise * q.posNoise * dt
        next[idx(2, 2)] += q.gyroNoise * q.gyroNoise * dt
        next[idx(3, 3)] += q.accelNoise * q.accelNoise * dt
        next[idx(4, 4)] += q.gyroBiasWalk * q.gyroBiasWalk * dt
        next.copyInto(p)
    }

    fun updatePosition(e: Double, n: Double, sigma: Double): Boolean {
        require(sigma > 0 && sigma.isFinite()) { "sigma must be positive and finite" }
        return update(
            arrayOf(unit(0), unit(1)), doubleArrayOf(e - x[0], n - x[1]),
            doubleArrayOf(sigma * sigma, sigma * sigma), config.gateChi2Pos,
        )
    }

    fun updateSpeed(v: Double, sigma: Double): Boolean {
        require(sigma > 0 && sigma.isFinite()) { "sigma must be positive and finite" }
        return update(arrayOf(unit(3)), doubleArrayOf(v - x[3]), doubleArrayOf(sigma * sigma), config.gateChi2Scalar)
    }

    fun updateHeading(psi: Double, sigma: Double): Boolean {
        require(sigma > 0 && sigma.isFinite()) { "sigma must be positive and finite" }
        return update(
            arrayOf(unit(2)), doubleArrayOf(wrapAngle(psi - x[2])), doubleArrayOf(sigma * sigma), config.gateChi2Scalar,
        )
    }

    /**
     * Псевдоизмерение «на оси дороги»: прямая через (e0, n0) с азимутом theta (рад от севера по часовой).
     * Измерение и поправка чисто поперечные: ψ, v и b_g не меняются (частичное обновление Шмидта), а сдвиг
     * позиции проецируется на нормаль. Без проекции при анизотропной P (вдоль дороги σ велика) K·y имеет
     * вдоль-дорожную составляющую, и на дуге фильтр отставал от истины на ~1 м/с.
     */
    fun updateLateral(e0: Double, n0: Double, theta: Double, sigma: Double): Boolean {
        require(sigma > 0 && sigma.isFinite()) { "sigma must be positive and finite" }
        val ne = cos(theta); val nn = -sin(theta) // нормаль к направлению (sin θ, cos θ)
        val offset = ne * (x[0] - e0) + nn * (x[1] - n0)
        return update(
            arrayOf(doubleArrayOf(ne, nn, 0.0, 0.0, 0.0)), doubleArrayOf(-offset), doubleArrayOf(sigma * sigma),
            config.gateChi2Scalar, intArrayOf(0, 1), doubleArrayOf(ne, nn),
        )
    }

    /** Дисперсия позиции поперёк направления theta: n·P[0:2,0:2]·nᵀ, n = (cos θ, −sin θ). */
    fun lateralVariance(theta: Double): Double {
        val ne = cos(theta); val nn = -sin(theta)
        return ne * ne * p[idx(0, 0)] + 2 * ne * nn * p[idx(0, 1)] + nn * nn * p[idx(1, 1)]
    }

    fun headingVariance(): Double = p[idx(2, 2)]

    /**
     * Возврат доверия к курсу и смещению гироскопа, когда они явно несогласованы с внешним ориентиром:
     * дисперсии ψ и b_g поднимаются не ниже заданных, их ковариации с остальными состояниями (и между собой)
     * обнуляются. Результат — блочно-диагональная P из главных подматриц исходной P, то есть остаётся
     * симметричной и неотрицательно определённой.
     */
    fun inflateHeading(minVarPsi: Double, minVarBias: Double) {
        require(minVarPsi >= 0 && minVarBias >= 0) { "variances must be >= 0" }
        for (s in intArrayOf(2, 4)) for (j in 0 until N) if (j != s) { p[idx(s, j)] = 0.0; p[idx(j, s)] = 0.0 }
        p[idx(2, 2)] = max(p[idx(2, 2)], minVarPsi)
        p[idx(4, 4)] = max(p[idx(4, 4)], minVarBias)
    }

    fun posSigma(): Double = sqrt(max(p[idx(0, 0)], p[idx(1, 1)]))

    /** χ²-расстояние фикса позиции до прогноза (как в гейте updatePosition), без изменения состояния. */
    fun positionD2(e: Double, n: Double, sigma: Double): Double {
        require(sigma > 0 && sigma.isFinite()) { "sigma must be positive and finite" }
        val r = sigma * sigma
        val s00 = p[idx(0, 0)] + r; val s01 = p[idx(0, 1)]; val s10 = p[idx(1, 0)]; val s11 = p[idx(1, 1)] + r
        val det = s00 * s11 - s01 * s10
        val ye = e - x[0]; val yn = n - x[1]
        return (ye * (s11 * ye - s01 * yn) + yn * (-s10 * ye + s00 * yn)) / det
    }

    /**
     * Общее обновление: H — строки (m ≤ 2), y — невязка, r — дисперсии шума (диагональ). С `onlyStates`
     * строки усиления остальных состояний обнуляются; с `alongNormal` (скалярное измерение, mask = позиция)
     * поправка позиции дополнительно проецируется на нормаль. Форма Джозефа верна для любого K.
     */
    private fun update(
        h: Array<DoubleArray>, y: DoubleArray, r: DoubleArray, gate: Double, onlyStates: IntArray? = null,
        alongNormal: DoubleArray? = null,
    ): Boolean {
        check(initialized) { "filter not initialized" }
        if (y.any { !it.isFinite() }) return false
        val m = h.size
        // PHᵀ (N×m)
        val pht = Array(N) { i -> DoubleArray(m) { k -> (0 until N).sumOf { j -> p[idx(i, j)] * h[k][j] } } }
        // S = H P Hᵀ + R (m×m)
        val s = Array(m) { a -> DoubleArray(m) { b -> (0 until N).sumOf { j -> h[a][j] * pht[j][b] } + if (a == b) r[a] else 0.0 } }
        val sInv = when (m) {
            1 -> arrayOf(doubleArrayOf(1.0 / s[0][0]))
            2 -> {
                val det = s[0][0] * s[1][1] - s[0][1] * s[1][0]
                arrayOf(doubleArrayOf(s[1][1] / det, -s[0][1] / det), doubleArrayOf(-s[1][0] / det, s[0][0] / det))
            }
            else -> error("measurement dimension $m not supported")
        }
        var d2 = 0.0
        for (a in 0 until m) for (b in 0 until m) d2 += y[a] * sInv[a][b] * y[b]
        if (!(d2 <= gate)) return false
        // K = PHᵀ S⁻¹ (N×m)
        val k = Array(N) { i -> DoubleArray(m) { b -> (0 until m).sumOf { a -> pht[i][a] * sInv[a][b] } } }
        if (onlyStates != null) for (i in 0 until N) if (i !in onlyStates) k[i].fill(0.0)
        if (alongNormal != null) { // поправка позиции только вдоль нормали: K_pos := n·(nᵀ K_pos)
            val kn = alongNormal[0] * k[0][0] + alongNormal[1] * k[1][0]
            k[0][0] = alongNormal[0] * kn; k[1][0] = alongNormal[1] * kn
        }
        for (i in 0 until N) x[i] += (0 until m).sumOf { a -> k[i][a] * y[a] }
        x[2] = wrapAngle(x[2])
        // Форма Джозефа: P = (I − KH) P (I − KH)ᵀ + K R Kᵀ
        val ikh = identity()
        for (i in 0 until N) for (j in 0 until N) ikh[idx(i, j)] -= (0 until m).sumOf { a -> k[i][a] * h[a][j] }
        val next = mulT(mul(ikh, p), ikh)
        for (i in 0 until N) for (j in 0 until N) next[idx(i, j)] += (0 until m).sumOf { a -> k[i][a] * r[a] * k[j][a] }
        next.copyInto(p)
        return true
    }

    private companion object {
        const val N = 5
        fun idx(i: Int, j: Int) = i * N + j
        fun identity() = DoubleArray(N * N).also { for (i in 0 until N) it[idx(i, i)] = 1.0 }
        fun unit(i: Int) = DoubleArray(N).also { it[i] = 1.0 }
        fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(N * N).also { out ->
            for (i in 0 until N) for (j in 0 until N) {
                var s = 0.0
                for (k in 0 until N) s += a[idx(i, k)] * b[idx(k, j)]
                out[idx(i, j)] = s
            }
        }
        /** a · bᵀ */
        fun mulT(a: DoubleArray, b: DoubleArray) = DoubleArray(N * N).also { out ->
            for (i in 0 until N) for (j in 0 until N) {
                var s = 0.0
                for (k in 0 until N) s += a[idx(i, k)] * b[idx(j, k)]
                out[idx(i, j)] = s
            }
        }
    }
}
