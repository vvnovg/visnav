package io.visnav.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefPackTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/refpack_fixture/$name")) { name }.readBytes()

    @Test fun parsesPythonWrittenFixture() {
        val rp = RefPack.parse(ByteBuffer.wrap(resource("refpack.bin")))
        assertEquals(3, rp.count)
        assertEquals(4, rp.dim)
        assertEquals(listOf(55.75, 55.7509, 55.7518), rp.lats.toList())
        assertEquals(listOf(37.6, 37.6, 37.6), rp.lons.toList())
        assertEquals(listOf(0f, 90f, 180f), rp.headings.toList())
        val row2 = (0 until 4).map { Half.toFloat(rp.descriptors[2 * 4 + it]) }
        assertEquals(0f, row2[0]); assertEquals(0.60009765625f, row2[1])
        assertEquals(0.7998046875f, row2[2]); assertEquals(0f, row2[3])
        assertEquals(1f, Half.toFloat(rp.descriptors[0]))
    }

    @Test fun parsesMeta() {
        val meta = RefPackMeta.parse(String(resource("refpack.json")))
        assertEquals("VNRP/1", meta.format)
        assertEquals("fixture", meta.model)
        assertEquals(480, meta.inputH); assertEquals(640, meta.inputW)
        assertEquals(3, meta.count); assertEquals(4, meta.dim)
    }

    @Test fun rejectsBadMagic() {
        val bytes = resource("refpack.bin").also { it[0] = 'X'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { RefPack.parse(ByteBuffer.wrap(bytes)) }
    }

    @Test fun rejectsTruncatedFile() {
        val bytes = resource("refpack.bin").copyOf(90)
        assertFailsWith<IllegalArgumentException> { RefPack.parse(ByteBuffer.wrap(bytes)) }
    }

    @Test fun magicConstantIsVnrpLittleEndian() {
        val b = ByteBuffer.wrap("VNRP".toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(RefPack.MAGIC, b.int)
    }
}
