package io.visnav.core

import kotlin.math.cos
import kotlin.math.sin

/** Прогноз позиции на «сейчас» для маркера и камеры карты; в фильтр и логи не идёт. */
object Nowcast {
    private const val MIN_SPEED_MPS = 1.0

    /** Позиция «сейчас» по выходу фильтра: сдвиг по курсу на v·Δt при v ≥ 1 м/с и конечном курсе, 0 ≤ Δt ≤ maxAheadMs. */
    fun at(out: LocalizerOutput, nowMs: Long, maxAheadMs: Long = 3000): DoubleArray {
        val dtMs = (nowMs - out.tMs).coerceIn(0L, maxAheadMs)
        val v = out.speedMps
        val psi = out.psiRad
        if (dtMs == 0L || !(v >= MIN_SPEED_MPS) || !v.isFinite() || !psi.isFinite()) return doubleArrayOf(out.lat, out.lon)
        val d = v * dtMs / 1000.0
        // Курс psi — от севера по часовой стрелке (как в Ekf2d): восток = sin, север = cos.
        return Enu(out.lat, out.lon).toLatLon(d * sin(psi), d * cos(psi))
    }
}
