package io.visnav.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class DescriptorLogTest {
    @Test fun writesContractLayoutAndReadsBack() {
        val f = File.createTempFile("test", ".desc")
        DescriptorLogWriter(f, dim = 3).use {
            it.write(1000L, floatArrayOf(1f, 0f, 0.5f))
            it.write(1500L, floatArrayOf(0f, -2f, 0.6f))
        }
        val raw = f.readBytes()
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("VNDS", String(raw, 0, 4))
        b.position(4)
        assertEquals(1, b.short.toInt()); assertEquals(1, b.short.toInt()); assertEquals(3, b.int)
        assertEquals(12 + 2 * (8 + 3 * 2), raw.size)
        assertEquals(1000L, b.long)

        val log = DescriptorLog.read(f)
        assertContentEquals(longArrayOf(1000L, 1500L), log.times)
        assertContentEquals(floatArrayOf(0f, -2f, 0.60009765625f), log.descriptor(1))
        assertEquals(1, log.indexOf(1500L))
        assertEquals(-1, log.indexOf(1234L))
    }

    @Test fun truncatedLastRecordIsSkipped() {
        val f = File.createTempFile("test", ".desc")
        DescriptorLogWriter(f, dim = 2).use {
            it.write(1L, floatArrayOf(1f, 1f)); it.write(2L, floatArrayOf(2f, 2f))
        }
        f.writeBytes(f.readBytes().copyOf(12 + 12 + 5))
        assertEquals(1, DescriptorLog.read(f).times.size)
    }

    @Test fun rejectsWrongDimension() {
        val f = File.createTempFile("test", ".desc")
        DescriptorLogWriter(f, dim = 2).use {
            kotlin.test.assertFailsWith<IllegalArgumentException> { it.write(1L, floatArrayOf(1f)) }
        }
    }

    @Test fun duplicateTimestampsKeepFirstIndex() {
        val f = File.createTempFile("test", ".desc")
        DescriptorLogWriter(f, dim = 2).use {
            it.write(1000L, floatArrayOf(1f, 1f))
            it.write(1000L, floatArrayOf(2f, 2f)) // duplicate timestamp
            it.write(2000L, floatArrayOf(3f, 3f))
        }
        val log = DescriptorLog.read(f)
        assertEquals(0, log.indexOf(1000L), "indexOf with duplicate timestamps returns first index")
    }

    @Test fun badDimDoesNotTruncateFile() {
        val f = File.createTempFile("test", ".desc")
        // Write initial content
        DescriptorLogWriter(f, dim = 3).use {
            it.write(100L, floatArrayOf(1f, 2f, 3f))
        }
        val originalSize = f.length()
        val originalContent = f.readBytes()

        // Try to open with invalid dim
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            DescriptorLogWriter(f, dim = 0)
        }

        // Verify file unchanged
        assertEquals(originalSize, f.length(), "file size unchanged after failed open")
        assertContentEquals(originalContent, f.readBytes(), "file content unchanged after failed open")
    }
}
