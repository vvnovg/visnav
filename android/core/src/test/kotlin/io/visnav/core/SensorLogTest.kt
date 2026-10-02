package io.visnav.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SensorLogTest {
    @Test fun exactLineFormat() {
        assertEquals("{\"v\":1,\"type\":\"sensors\",\"started_ms\":5}", SensorLogFormat.header(5))
        assertEquals("{\"t\":1000.5,\"k\":\"g\",\"x\":0.1,\"y\":-0.2,\"z\":0.3}",
            SensorLogFormat.line(GyroEvent(1000.5, 0.1f, -0.2f, 0.3f)))
        assertEquals("{\"t\":2000.0,\"k\":\"loc\",\"lat\":55.75,\"lon\":37.6,\"acc\":4.5,\"spd\":12.5,\"spd_acc\":null,\"brg\":90.0,\"brg_acc\":null}",
            SensorLogFormat.line(LocEvent(2000.0, 55.75, 37.6, 4.5f, 12.5f, null, 90f, null)))
        assertEquals("{\"t\":3000.0,\"k\":\"gnss\",\"sats\":20,\"used\":12,\"cn0\":null,\"cn0_std\":null,\"cn0_max\":null,\"used_gps\":null,\"used_glo\":null,\"used_gal\":null,\"used_bds\":null}",
            SensorLogFormat.line(GnssStatusEvent(3000.0, 20, 12, null)))
    }

    @Test fun parseRoundTripsEveryType() {
        val events = listOf(
            GyroEvent(1.0, 0.1f, 0.2f, 0.3f), AccelEvent(2.0, 0f, 0f, 9.81f),
            LocEvent(3.0, 55.75, 37.6, 3f, 10f, 0.5f, 270f, 2f), GnssStatusEvent(4.0, 18, 9, 31.5f),
        )
        for (e in events) assertEquals(e, SensorLogFormat.parse(SensorLogFormat.line(e)))
        assertNull(SensorLogFormat.parse(SensorLogFormat.header(1)))
    }

    // R1: контракт .sensors.jsonl v1 расширяется аддитивно — читатели ИГНОРИРУЮТ неизвестные виды событий,
    // а не бросают исключение (иначе новый писатель ломает старый читатель на любой новой строке).
    @Test fun parseIgnoresUnknownEventKind() {
        assertNull(SensorLogFormat.parse("{\"t\":1.0,\"k\":\"zz\"}"))
    }

    @Test fun readSensorLogSkipsUnknownEventKindInTheMiddle() {
        val f = File.createTempFile("sen", ".sensors.jsonl")
        f.writeText(
            SensorLogFormat.header(1) + "\n" +
                SensorLogFormat.line(GyroEvent(10.0, 0f, 0f, 0f)) + "\n" +
                "{\"t\":15.0,\"k\":\"zz\"}\n" +
                SensorLogFormat.line(AccelEvent(20.0, 0f, 0f, 9.8f)) + "\n"
        )
        val read = readSensorLog(f)
        assertEquals(listOf(10.0, 20.0), read.map { it.tMs })
    }

    @Test fun clockAndGyroUncalAndFrameCaptureRoundTrip() {
        val events = listOf(
            ClockEvent(1.0, 1_700_000_000_000L, 123_456_789_000L, 987_654_321_000L),
            GyroUncalEvent(2.0, 0.1f, 0.2f, 0.3f, 0.01f, 0.02f, 0.03f),
            FrameCaptureEvent(4_500.0, 4_500L, 987_654_000L),
        )
        for (e in events) {
            assertEquals(true, SensorLogFormat.isWritable(e))
            assertEquals(e, SensorLogFormat.parse(SensorLogFormat.line(e)))
        }
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
        var closeCalled = false
        override fun write(cbuf: CharArray, off: Int, len: Int) = throw java.io.IOException("boom")
        override fun flush() = throw java.io.IOException("boom")
        override fun close() { closeCalled = true }
    }

    @Test fun ioExceptionDuringWriteIsCountedNotThrown() {
        val log = SensorLogger(ThrowingWriter())
        log.event(AccelEvent(1.0, 0f, 0f, 9.8f))
        assertEquals(1, log.failed)
        // Further events are no-ops once broken.
        log.event(AccelEvent(2.0, 0f, 0f, 9.8f))
        assertEquals(1, log.failed)
    }

    @Test fun onFirstFailureFiresOnceOnFirstIOException() {
        val log = SensorLogger(ThrowingWriter())
        val seen = mutableListOf<String?>()
        log.onFirstFailure = { e -> seen.add(e.message) }
        log.event(AccelEvent(1.0, 0f, 0f, 9.8f))
        log.event(AccelEvent(2.0, 0f, 0f, 9.8f)) // already broken — event() is a no-op, no second callback
        assertEquals(listOf<String?>("boom"), seen)
        assertEquals(1, log.failed)
    }

    @Test fun headerFailureAlsoCountsAndFiresOnFirstFailure() {
        val log = SensorLogger(ThrowingWriter())
        var fired = false
        log.onFirstFailure = { fired = true }
        log.header(1)
        assertEquals(true, fired)
        assertEquals(1, log.failed)
    }

    @Test fun closeReleasesWriterEvenIfFlushThrowsAfterFailure() {
        val w = ThrowingWriter()
        val log = SensorLogger(w)
        log.event(AccelEvent(1.0, 0f, 0f, 9.8f)) // marks the logger broken via an IOException
        assertEquals(1, log.failed)
        assertFailsWith<java.io.IOException> { log.close() }
        assertEquals(true, w.closeCalled)
    }

    @Test fun gnssStatusExtendedFieldsRoundTrip() {
        val e = GnssStatusEvent(5.0, 30, 14, 33.5f, 4.25f, 41f, 6, 4, 3, 1)
        assertEquals("{\"t\":5.0,\"k\":\"gnss\",\"sats\":30,\"used\":14,\"cn0\":33.5,\"cn0_std\":4.25,\"cn0_max\":41.0," +
            "\"used_gps\":6,\"used_glo\":4,\"used_gal\":3,\"used_bds\":1}", SensorLogFormat.line(e))
        assertEquals(e, SensorLogFormat.parse(SensorLogFormat.line(e)))
    }

    @Test fun gnssStatusM2aLineStillParses() {
        val e = SensorLogFormat.parse("{\"t\":3000.0,\"k\":\"gnss\",\"sats\":20,\"used\":12,\"cn0\":null}")
        assertEquals(GnssStatusEvent(3000.0, 20, 12, null), e)
    }

    @Test fun agcEventRoundTripAndWritability() {
        val e = AgcEvent(7.0, -2.5f, 12)
        assertEquals("{\"t\":7.0,\"k\":\"agc\",\"agc_db\":-2.5,\"n\":12}", SensorLogFormat.line(e))
        assertEquals(e, SensorLogFormat.parse(SensorLogFormat.line(e)))
        assertFalse(SensorLogFormat.isWritable(AgcEvent(7.0, Float.NaN, 1)))
    }
}
