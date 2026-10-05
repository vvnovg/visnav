package io.visnav.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DebugPerfTextTest {
    @Test fun thermalLabelsForAllLevels() {
        val expected = listOf("нет", "слабый", "умеренный", "сильный", "критический", "аварийный", "отключение")
        assertEquals(expected, (0..6).map { thermalLabel(it) })
    }

    @Test fun thermalLabelUnknownOrMissing() {
        assertEquals("—", thermalLabel(null))
        assertEquals("—", thermalLabel(7))
        assertEquals("—", thermalLabel(-1))
    }

    @Test fun perfLineWithValues() {
        assertEquals(
            "Кадр: каждые 750 мс · Нагрев: умеренный · E2E p50: 123 мс · Ток: -412 мА",
            perfLine(PerfUi(750, 2, 123.4, -412.3)),
        )
    }

    @Test fun perfLineWithMissingValues() {
        assertEquals("Кадр: каждые 500 мс · Нагрев: — · E2E p50: — · Ток: —", perfLine(PerfUi(500, null, null, null)))
        assertEquals("Кадр: каждые — · Нагрев: — · E2E p50: — · Ток: —", perfLine(null))
    }

    @Test fun settingLabels() {
        assertEquals("Полная", profileLabel(PerfSettings.PROFILE_FULL))
        assertEquals("База (без камеры)", profileLabel(PerfSettings.PROFILE_BASELINE))
        assertEquals("CPU", ortLabel(PerfSettings.ORT_CPU))
        assertEquals("XNNPACK", ortLabel(PerfSettings.ORT_XNNPACK))
        assertEquals("без ограничения", durationLabel(0))
        assertEquals("30 мин", durationLabel(30))
    }
}
