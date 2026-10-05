package io.visnav.core

/** Команды адаптера ELM327 и разбор его ответов (скорость, PID 0D режима 01). */
object Elm327 {
    val INIT: List<String> = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100")
    const val SPEED = "010D"

    private val SPEED_RE = Regex("41\\s*0D\\s*([0-9A-Fa-f]{2})", RegexOption.IGNORE_CASE)
    private val ERRORS = listOf("NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "STOPPED", "ERROR")

    /** Скорость, км/ч, из ответа на 010D; null — нет данных или ошибка. */
    fun parseSpeed(response: String): Int? =
        SPEED_RE.find(response.replace(">", ""))?.groupValues?.get(1)?.toInt(16)

    /** Ответ — ошибка адаптера или шины (NO DATA, ?, UNABLE TO CONNECT, CAN ERROR, BUS ERROR, STOPPED, ERROR). */
    fun isError(response: String): Boolean =
        response.replace(">", "").split('\r', '\n').map { it.trim().uppercase() }.any { line ->
            line == "?" || ERRORS.any { line.contains(it) }
        }
}
