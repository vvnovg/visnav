package io.visnav.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Прогноз позиции на «сейчас» для маркера и камеры карты; в фильтр и логи не идёт. */
object Nowcast {
    private const val MIN_SPEED_MPS = 1.0
    /** У полюса масштаб по долготе вырождается — прогноз не делается. */
    private const val MAX_ABS_LAT = 89.0

    /**
     * Позиция «сейчас» по выходу фильтра: сдвиг по курсу на v·Δt при v ≥ 1 м/с и конечном курсе,
     * 0 ≤ Δt ≤ maxAheadMs; у полюса (|lat| > 89) без сдвига; долгота сдвинутой точки — в [−180, 180).
     */
    fun at(out: LocalizerOutput, nowMs: Long, maxAheadMs: Long = 3000): DoubleArray {
        require(maxAheadMs >= 0) { "maxAheadMs must be >= 0" }
        val dtMs = (nowMs - out.tMs).coerceIn(0L, maxAheadMs)
        val v = out.speedMps
        val psi = out.psiRad
        val noShift = dtMs == 0L || !(v >= MIN_SPEED_MPS) || !v.isFinite() || !psi.isFinite() ||
            !out.lat.isFinite() || abs(out.lat) > MAX_ABS_LAT
        if (noShift) return doubleArrayOf(out.lat, out.lon)
        val d = v * dtMs / 1000.0
        // Курс psi — от севера по часовой стрелке (как в Ekf2d): восток = sin, север = cos.
        val p = Enu(out.lat, out.lon).toLatLon(d * sin(psi), d * cos(psi))
        p[1] = (p[1] + 180.0).mod(360.0) - 180.0
        return p
    }
}
