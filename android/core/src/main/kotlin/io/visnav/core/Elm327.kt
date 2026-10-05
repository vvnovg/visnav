package io.visnav.core

/** Команды адаптера ELM327 и разбор его ответов (скорость, PID 0D режима 01). */
object Elm327 {
    val INIT: List<String> = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100")
    const val SPEED = "010D"

    private val HEX = Regex("[0-9A-F]+")
    private val ISO_TARGETS = setOf("6B", "F1")
    private val ERR_CODE = Regex("ERR[0-9A-F]{2}")
    private val ERRORS = listOf(
        "NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "STOPPED", "ERROR",
        "BUFFER FULL", "BUS BUSY", "ACT ALERT", "LP ALERT", "LV RESET",
    )

    /**
     * Скорость, км/ч, из ответа на 010D; null — нет данных или ошибка. Каждая строка разбирается
     * отдельно (пробелы и табуляции убираются, строка должна быть шестнадцатеричной): `41 0D xx` —
     * в начале строки, после заголовка CAN 11 бит (`7E8 03`) или 29 бит (`18DAF110 03`) с PCI 03
     * (допускается до 4 байтов заполнения кадра — ATH1 у части клонов печатает все 8 байтов),
     * либо после заголовка ISO/KWP из трёх байтов со вторым байтом 6B (J1850/ISO 9141: `48 6B 10`)
     * или F1 (KWP: `83 F1 11`), допускается байт контрольной суммы. KWP с отдельным байтом длины
     * (`80 F1 11 03 …`) не распознаётся — редкость, при ATH0 заголовков нет.
     * Если отвечают несколько ЭБУ с разными значениями — берётся первое.
     */
    fun parseSpeed(response: String): Int? {
        for (raw in lines(response)) {
            val s = raw.replace(" ", "").replace("\t", "")
            if (s.isEmpty() || !HEX.matches(s)) continue
            val v = speedAt(s, 0, 0)
                ?: canSpeed(s, 3)
                ?: canSpeed(s, 8)
                ?: isoSpeed(s)
            if (v != null) return v
        }
        return null
    }

    /** Ответ — ошибка адаптера или шины (NO DATA, ?, UNABLE TO CONNECT, CAN ERROR, BUS ERROR, STOPPED, ERROR,
     * BUFFER FULL, BUS BUSY, ACT ALERT, LP ALERT, LV RESET, ERRxx, баннер сброса ELM327). */
    fun isError(response: String): Boolean = lines(response).any { line ->
        line == "?" || ERRORS.any { line.contains(it) } || line.contains("ELM327") || ERR_CODE.matches(line)
    }

    /** Адаптер сбросился (баннер ELM327 где угодно в строке — после просадки питания перед ним бывает мусор — или LV RESET). */
    fun isReset(response: String): Boolean = lines(response).any { it.contains("ELM327") || it.contains("LV RESET") }

    private fun lines(response: String): List<String> =
        response.replace(">", "").split('\r', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    /** Заголовок CAN длиной [headerLen] символов, затем PCI 03 и `410Dxx`, после — до 4 байтов заполнения. */
    private fun canSpeed(s: String, headerLen: Int): Int? =
        if (s.startsWith("03", headerLen)) speedAt(s, headerLen + 2, 4) else null

    /** Заголовок ISO/KWP из трёх байтов со вторым байтом 6B или F1, затем `410Dxx` и не больше байта контрольной суммы. */
    private fun isoSpeed(s: String): Int? =
        if (s.length >= 6 && s.substring(2, 4) in ISO_TARGETS) speedAt(s, 6, 1) else null

    /** `410Dxx` с позиции [off], после значения — не больше [maxExtraBytes] байтов. */
    private fun speedAt(s: String, off: Int, maxExtraBytes: Int): Int? {
        val extra = s.length - (off + 6)
        if (!s.startsWith("410D", off) || extra < 0 || extra % 2 != 0 || extra / 2 > maxExtraBytes) return null
        return s.substring(off + 4, off + 6).toInt(16)
    }
}
