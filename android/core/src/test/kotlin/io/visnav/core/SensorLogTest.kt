package io.visnav.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SensorLogTest {
    @Test fun exactLineFormat() {
        assertEquals("{\"v\":1,\"type\":\"sensors\",\"started_ms\":5}", SensorLogFormat.header(5))
        assertEquals("{\"t\":1000.5,\"k\":\"g\",\"x\":0.1,\"y\":-0.2,\"z\":0.3}",
            SensorLogFormat.line(GyroEvent(1000.5, 0.1f, -0.2f, 0.3f)))
        assertEquals("{\"t\":2000.0,\"k\":\"loc\",\"lat\":55.75,\"lon\":37.6,\"acc\":4.5,\"spd\":12.5,\"spd_acc\":null,\"brg\":90.0,\"brg_acc\":null}",
            SensorLogFormat.line(LocEvent(2000.0, 55.75, 37.6, 4.5f, 12.5f, null, 90f, null)))
        assertEquals("{\"t\":3000.0,\"k\":\"gnss\",\"sats\":20,\"used\":12,\"cn0\":null}",
            SensorLogFormat.line(GnssStatusEvent(3000.0, 20, 12, null)))
    }

    @Test fun parseRoundTripsEveryType() {
        val events = listOf(
            GyroEvent(1.0, 0.1f, 0.2f, 0.3f), AccelEvent(2.0, 0f, 0f, 9.81f),
            LocEvent(3.0, 55.75, 37.6, 3f, 10f, 0.5f, 270f, 2f), GnssStatusEvent(4.0, 18, 9, 31.5f),
        )
        for (e in events) assertEquals(e, SensorLogFormat.parse(SensorLogFormat.line(e)))
        assertNull(SensorLogFormat.parse(SensorLogFormat.header(1)))
        assertFailsWith<IllegalArgumentException> { SensorLogFormat.parse("{\"t\":1.0,\"k\":\"zz\"}") }
    }

    @Test fun loggerWritesAndReaderSortsAndSkipsTruncatedTail() {
        val f = File.createTempFile("sen", ".sensors.jsonl")
        SensorLogger(f).use {
            it.header(1)
            it.event(AccelEvent(20.0, 0f, 0f, 9.8f))
            it.event(GyroEvent(10.0, 0f, 0f, 0f))
        }
        f.appendText("{\"t\":30.0,\"k\":\"g\",\"x\":0.")
        val read = readSensorLog(f)
        assertEquals(listOf(10.0, 20.0), read.map { it.tMs })
    }

    @Test fun nanNullableFieldsWriteNull() {
        val locWithNanSpeed = LocEvent(2000.0, 55.75, 37.6, 4.5f, Float.NaN, null, 90f, null)
        val line = SensorLogFormat.line(locWithNanSpeed)
        assertEquals("{\"t\":2000.0,\"k\":\"loc\",\"lat\":55.75,\"lon\":37.6,\"acc\":4.5,\"spd\":null,\"spd_acc\":null,\"brg\":90.0,\"brg_acc\":null}", line)
        assertEquals(LocEvent(2000.0, 55.75, 37.6, 4.5f, null, null, 90f, null), SensorLogFormat.parse(line))
    }

    @Test fun isWritableRejectsNanInRequiredFields() {
        assertEquals(true, SensorLogFormat.isWritable(GyroEvent(1.0, 0.1f, 0.2f, 0.3f)))
        assertEquals(false, SensorLogFormat.isWritable(GyroEvent(1.0, Float.NaN, 0.2f, 0.3f)))
        assertEquals(false, SensorLogFormat.isWritable(AccelEvent(1.0, 0f, 0f, Float.POSITIVE_INFINITY)))
        assertEquals(true, SensorLogFormat.isWritable(LocEvent(1.0, 55.75, 37.6, 4.5f, Float.NaN, null, 90f, null)))
        assertEquals(false, SensorLogFormat.isWritable(LocEvent(1.0, Double.NaN, 37.6, 4.5f, 10f, null, 90f, null)))
        assertEquals(true, SensorLogFormat.isWritable(GnssStatusEvent(1.0, 20, 12, Float.NaN)))
    }

    @Test fun loggerSkipsNonWritableEvents() {
        val f = File.createTempFile("sen", ".sensors.jsonl")
        SensorLogger(f).use {
            it.header(1)
            it.event(GyroEvent(10.0, 0f, 0f, 0f))
            it.event(GyroEvent(15.0, Float.NaN, 0f, 0f))
            it.event(GyroEvent(20.0, 0f, 0f, 0f))
            assertEquals(1, it.skipped)
        }
        val read = readSensorLog(f)
        assertEquals(listOf(10.0, 20.0), read.map { it.tMs })
    }

    @Test fun eventAfterCloseIsIgnoredWithoutException() {
        val f = File.createTempFile("sen", ".sensors.jsonl")
        val log = SensorLogger(f)
        log.header(1)
        log.close()
        log.event(GyroEvent(10.0, 0f, 0f, 0f)) // no exception, no-op
        assertEquals(0, log.failed)
        // Closing again is also a no-op (idempotent).
        log.close()
    }

    private class ThrowingWriter : java.io.Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = throw java.io.IOException("boom")
        override fun flush() = throw java.io.IOException("boom")
        override fun close() = Unit
    }

    @Test fun ioExceptionDuringWriteIsCountedNotThrown() {
        val log = SensorLogger(ThrowingWriter())
        log.event(AccelEvent(1.0, 0f, 0f, 9.8f))
        assertEquals(1, log.failed)
        // Further events are no-ops once broken.
        log.event(AccelEvent(2.0, 0f, 0f, 9.8f))
        assertEquals(1, log.failed)
    }
}
