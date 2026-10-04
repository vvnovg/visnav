package io.visnav.core

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfLogTest {
    private fun obj(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject
    private fun JsonObject.raw(k: String) = getValue(k).jsonPrimitive.toString()
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun <T> withGermanLocale(block: () -> T): T {
        val old = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try { return block() } finally { Locale.setDefault(old) }
    }

    @Test fun headerFields() = withGermanLocale {
        val o = obj(PerfLog.header(123L, "Pixel \"7\"", "full", 1500L, "xnnpack", 4500))
        assertEquals("perf", o.str("type"))
        assertEquals("1", o.raw("v"))
        assertEquals("123", o.raw("session_started_ms"))
        assertEquals("Pixel \"7\"", o.str("device"))
        assertEquals("full", o.str("profile"))
        assertEquals("1500", o.raw("reorder_delay_ms"))
        assertEquals("xnnpack", o.str("ort"))
        assertEquals("4500", o.raw("battery_capacity_mah"))
        assertEquals(JsonNull, obj(PerfLog.header(1L, "d", "baseline", 300L, "cpu", null))["battery_capacity_mah"])
    }

    @Test fun frameFieldsUseDotAndOneDecimal() = withGermanLocale {
        val line = PerfLog.frame(1000L, 12.34, 56.78, 3.0, 0.25, 1.96, 80.04, 500L)
        assertFalse(line.contains("12,3"), line)
        val o = obj(line)
        assertEquals("frame", o.str("type"))
        assertEquals("1000", o.raw("t_ms"))
        assertEquals("12.3", o.raw("pre"))
        assertEquals("56.8", o.raw("inf"))
        assertEquals("3.0", o.raw("search"))
        assertEquals("0.3", o.raw("fuse_ms"))
        assertEquals("2.0", o.raw("nav_ms"))
        assertEquals("80.0", o.raw("e2e_ms"))
        assertEquals("500", o.raw("interval_ms"))
    }

    @Test fun sysFieldsAndNulls() = withGermanLocale {
        val full = obj(PerfLog.sys(SysSample(5L, 87.5, 3_000_000L, -450_000L, 31.2, false, 2, 0.81, 750L)))
        assertEquals("sys", full.str("type"))
        assertEquals("5", full.raw("t_ms"))
        assertEquals("87.5", full.raw("batt_pct"))
        assertEquals("3000000", full.raw("charge_uah"))
        assertEquals("-450000", full.raw("current_ua"))
        assertEquals("31.2", full.raw("batt_temp_c"))
        assertEquals("false", full.raw("plugged"))
        assertEquals("2", full.raw("thermal"))
        assertEquals("0.81", full.raw("headroom"))
        assertEquals("750", full.raw("interval_ms"))
        val empty = obj(PerfLog.sys(SysSample(6L, null, null, null, null, true, null, null, 500L)))
        for (k in listOf("batt_pct", "charge_uah", "current_ua", "batt_temp_c", "thermal", "headroom")) {
            assertEquals(JsonNull, empty[k], k)
        }
        assertEquals("true", empty.raw("plugged"))
    }

    @Test fun lateFields() = withGermanLocale {
        val stats = linkedMapOf(
            "frame" to LatenessStats(3, 120.25, 300.0, 410.5),
            "imu" to LatenessStats(10, 5.0, 9.96, 12.0),
        )
        val o = obj(PerfLog.late(7L, stats, 2, 1))
        assertEquals("late", o.str("type"))
        assertEquals("7", o.raw("t_ms"))
        assertEquals("2", o.raw("late"))
        assertEquals("1", o.raw("dropped"))
        val src = o.getValue("sources").jsonObject
        val f = src.getValue("frame").jsonObject
        assertEquals("3", f.raw("n"))
        assertEquals("120.3", f.raw("p50"))
        assertEquals("300.0", f.raw("p99"))
        assertEquals("410.5", f.raw("max"))
        assertEquals("10.0", src.getValue("imu").jsonObject.raw("p99"))
        assertTrue(obj(PerfLog.late(8L, emptyMap(), 0, 0)).getValue("sources").jsonObject.isEmpty())
    }
}
