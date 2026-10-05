package io.visnav.core

/** Команды адаптера ELM327 и разбор его ответов (скорость, PID 0D режима 01). */
object Elm327 {
    val INIT: List<String> = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100")
    const val SPEED = "010D"

    private val HEX = Regex("[0-9A-F]+")
    private val ERR_CODE = Regex("ERR[0-9A-F]{2}")
    private val ERRORS = listOf(
        "NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "STOPPED", "ERROR",
        "BUFFER FULL", "BUS BUSY", "ACT ALERT", "LP ALERT", "LV RESET",
    )

    /**
     * Скорость, км/ч, из ответа на 010D; null — нет данных или ошибка. Каждая строка разбирается
     * отдельно (пробелы и табуляции убираются, строка должна быть шестнадцатеричной): `41 0D xx` —
     * в начале строки, после заголовка CAN 11 бит (`7E8 03`) или 29 бит (`18DAF110 03`) с PCI 03,
     * либо после заголовка ISO/KWP из трёх байтов (`48 6B 10`, допускается байт контрольной суммы).
     * Если отвечают несколько ЭБУ с разными значениями — берётся первое.
     */
    fun parseSpeed(response: String): Int? {
        for (raw in lines(response)) {
            val s = raw.replace(" ", "").replace("\t", "")
            if (s.isEmpty() || !HEX.matches(s)) continue
            val v = speedAt(s, 0, 0)
                ?: canSpeed(s, 3)
                ?: canSpeed(s, 8)
                ?: speedAt(s, 6, 1)
            if (v != null) return v
        }
        return null
    }

    /** Ответ — ошибка адаптера или шины (NO DATA, ?, UNABLE TO CONNECT, CAN ERROR, BUS ERROR, STOPPED, ERROR,
     * BUFFER FULL, BUS BUSY, ACT ALERT, LP ALERT, LV RESET, ERRxx, баннер сброса ELM327). */
    fun isError(response: String): Boolean = lines(response).any { line ->
        line == "?" || ERRORS.any { line.contains(it) } || line.startsWith("ELM327") || ERR_CODE.matches(line)
    }

    /** Адаптер сбросился (баннер ELM327 или LV RESET) — инициализацию нужно повторить. */
    fun isReset(response: String): Boolean = lines(response).any { it.startsWith("ELM327") || it.contains("LV RESET") }

    private fun lines(response: String): List<String> =
        response.replace(">", "").split('\r', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    /** Заголовок CAN длиной [headerLen] символов, затем PCI 03 и `410Dxx` без лишних байтов. */
    private fun canSpeed(s: String, headerLen: Int): Int? =
        if (s.startsWith("03", headerLen)) speedAt(s, headerLen + 2, 0) else null

    /** `410Dxx` с позиции [off], после значения — не больше [maxExtraBytes] байтов. */
    private fun speedAt(s: String, off: Int, maxExtraBytes: Int): Int? {
        val extra = s.length - (off + 6)
        if (!s.startsWith("410D", off) || extra < 0 || extra % 2 != 0 || extra / 2 > maxExtraBytes) return null
        return s.substring(off + 4, off + 6).toInt(16)
    }
}
