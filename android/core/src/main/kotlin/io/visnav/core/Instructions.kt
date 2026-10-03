package io.visnav.core

import kotlin.math.roundToInt

/** Русские тексты подсказок: без склонения названий улиц («… — Тверская улица»). */
object Instructions {
    private val ORDINALS = listOf("первый", "второй", "третий", "четвёртый", "пятый", "шестой", "седьмой", "восьмой", "девятый")

    fun ordinal(n: Int): String = if (n in 1..9) ORDINALS[n - 1] else "$n-й"

    fun distanceText(m: Double): String = when {
        m < 100 -> "${maxOf(10, (m / 10).roundToInt() * 10)} м"
        else -> {
            val r = (m / 50).roundToInt() * 50
            if (r < 1000) "$r м"
            else "%.1f".format(java.util.Locale.ROOT, (m / 100).roundToInt() / 10.0).removeSuffix(".0").replace('.', ',') + " км"
        }
    }

    private fun action(m: Maneuver): String = when (m.type) {
        ManeuverType.LEFT -> "поверните налево"
        ManeuverType.RIGHT -> "поверните направо"
        ManeuverType.SLIGHT_LEFT -> "держитесь левее"
        ManeuverType.SLIGHT_RIGHT -> "держитесь правее"
        ManeuverType.SHARP_LEFT -> "резко поверните налево"
        ManeuverType.SHARP_RIGHT -> "резко поверните направо"
        ManeuverType.UTURN -> "развернитесь"
        ManeuverType.CONTINUE -> "продолжайте прямо"
        ManeuverType.ROUNDABOUT -> "на круговом движении ${ordinal(m.exit)} съезд"
        ManeuverType.DEPART -> "начните движение"
        ManeuverType.ARRIVE -> "пункт назначения"
    }

    fun prompt(m: Maneuver, distM: Double?): String {
        if (m.type == ManeuverType.ARRIVE) {
            return if (distM == null) "Вы прибыли" else "Через ${distanceText(distM)} — пункт назначения"
        }
        val street = if (m.street != null && m.type != ManeuverType.DEPART) " — ${m.street}" else ""
        val act = action(m)
        return if (distM == null) act.replaceFirstChar { it.uppercase() } + street
        else "Через ${distanceText(distM)} $act$street"
    }
}
