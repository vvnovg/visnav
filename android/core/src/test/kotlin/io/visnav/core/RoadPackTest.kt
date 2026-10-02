package io.visnav.core

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoadPackTest {
    private val fixture = File("../../research/vpr_bench/tests/data/roadpack_fixture")

    @Test fun parsesCrossLanguageFixture() {
        val pack = RoadPack.parse(ByteBuffer.wrap(File(fixture, "roadpack.bin").readBytes()))
        assertEquals(3, pack.nodeCount); assertEquals(2, pack.edgeCount)
        assertEquals(37.602, pack.lons[2], 1e-12)
        assertEquals(listOf(101L, 202L), pack.way.toList())
        assertFalse(pack.oneway(0)); assertTrue(pack.oneway(1)); assertTrue(pack.tunnel(1))
        assertEquals(RoadClass.RESIDENTIAL, pack.roadClass(0)); assertEquals(RoadClass.PRIMARY, pack.roadClass(1))
        val meta = RoadPackMeta.parse(File(fixture, "roadpack.json").readText())
        assertEquals("2026-10-02T00:00:00Z", meta.createdAt); assertEquals(2, meta.edgeCount)
    }

    @Test fun rejectsBadMagicAndSize() {
        val raw = File(fixture, "roadpack.bin").readBytes()
        assertFailsWith<IllegalArgumentException> { RoadPack.parse(ByteBuffer.wrap(raw.copyOf(raw.size - 1))) }
        val bad = raw.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { RoadPack.parse(ByteBuffer.wrap(bad)) }
    }

    @Test fun rejectsEdgeOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            RoadPack(doubleArrayOf(0.0), doubleArrayOf(0.0), longArrayOf(1), intArrayOf(0), intArrayOf(1),
                byteArrayOf(0), byteArrayOf(7))
        }
    }

    @Test fun lateralSigmaByClass() {
        assertEquals(7.0, RoadClass.lateralSigmaM(RoadClass.MOTORWAY))
        assertEquals(6.0, RoadClass.lateralSigmaM(RoadClass.PRIMARY))
        assertEquals(4.0, RoadClass.lateralSigmaM(RoadClass.RESIDENTIAL))
    }
}
