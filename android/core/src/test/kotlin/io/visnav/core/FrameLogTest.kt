package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameLogTest {
    // Та же строка, что в research/vpr_bench/tests/test_fieldlog.py (SAMPLE_LINE) — контракт с Python.
    private val sample = "{\"v\":1,\"type\":\"frame\",\"t_ms\":1700000000123,\"mode\":\"gps\"," +
        "\"gps\":{\"lat\":55.75,\"lon\":37.6,\"acc_m\":4.5,\"t_ms\":1700000000000}," +
        "\"prior\":{\"lat\":55.75,\"lon\":37.6,\"radius_m\":500.0}," +
        "\"top\":[{\"i\":1,\"sim\":0.75,\"lat\":55.7509,\"lon\":37.6}]," +
        "\"fix\":{\"lat\":55.7509,\"lon\":37.6,\"sim\":0.75}," +
        "\"lat_ms\":{\"pre\":10.5,\"inf\":80.25,\"search\":2.0}}"

    @Test fun frameLineMatchesPythonContract() {
        val r = FrameRecord(
            tMs = 1_700_000_000_123, mode = "gps",
            gps = GpsJson(55.75, 37.6, 4.5f, 1_700_000_000_000),
            prior = PriorJson(55.75, 37.6, 500.0),
            top = listOf(HitJson(1, 0.75f, 55.7509, 37.6)),
            fix = FixJson(55.7509, 37.6, 0.75f),
            latMs = LatencyJson(10.5, 80.25, 2.0),
        )
        assertEquals(sample, LogJson.line(r))
    }

    @Test fun nullsAreWrittenExplicitly() {
        val r = FrameRecord(tMs = 1, mode = "visual", gps = null, prior = null, top = emptyList(), fix = null,
            latMs = LatencyJson(0.0, 0.0, 0.0))
        assertEquals(
            "{\"v\":1,\"type\":\"frame\",\"t_ms\":1,\"mode\":\"visual\",\"gps\":null,\"prior\":null,\"top\":[]," +
                "\"fix\":null,\"lat_ms\":{\"pre\":0.0,\"inf\":0.0,\"search\":0.0}}",
            LogJson.line(r),
        )
    }

    @Test fun sessionHeaderLine() {
        val h = SessionHeader(model = "m", refpackCreatedAt = "2026-09-27T00:00:00Z", device = "d",
            startedMs = 5, mode = "gps")
        assertEquals(
            "{\"v\":1,\"type\":\"session\",\"model\":\"m\",\"refpack_created_at\":\"2026-09-27T00:00:00Z\"," +
                "\"device\":\"d\",\"started_ms\":5,\"mode\":\"gps\"}",
            LogJson.line(h),
        )
    }
}
