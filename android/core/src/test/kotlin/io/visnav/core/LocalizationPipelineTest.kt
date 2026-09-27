package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalizationPipelineTest {
    private val one: Short = 0x3C00
    private val pack = RefPack(
        3, 3, doubleArrayOf(55.7500, 55.7509, 55.7518), doubleArrayOf(37.6, 37.6, 37.6),
        floatArrayOf(0f, 0f, 0f), shortArrayOf(one, 0, 0, 0, one, 0, 0, 0, one),
    )

    private class FakeEmbedder(val out: FloatArray) : Embedder {
        override val name = "fake"
        var calls = 0
        override fun embed(rgb: ByteArray, width: Int, height: Int): FloatArray { calls++; return out }
    }

    private fun clock(vararg ns: Long): () -> Long { var i = 0; return { ns[i++] } }

    @Test fun gpsModeRecordsFixPriorAndLatency() {
        val emb = FakeEmbedder(floatArrayOf(0f, 1f, 0f))
        val p = LocalizationPipeline(pack, emb, PriorPolicy(PriorMode.GPS), k = 2,
            nanoTime = clock(0, 40_000_000, 43_000_000))
        val r = p.process(1_000, ByteArray(12), 2, 2, GpsFix(55.7509, 37.6, 4f, 990), preMs = 7.0)
        assertEquals("gps", r.mode)
        assertEquals(PriorJson(55.7509, 37.6, 500.0), r.prior)
        assertEquals(1, r.top[0].i) // окно 500 м вокруг эталона 1 содержит все три; у 0 и 2 сходство 0
        assertEquals(2, r.top.size)
        assertEquals(FixJson(55.7509, 37.6, 1f), r.fix)
        assertEquals(LatencyJson(7.0, 40.0, 3.0), r.latMs)
        assertEquals(1, emb.calls)
    }

    @Test fun noGpsNoWindowSearchesEverything() {
        val p = LocalizationPipeline(pack, FakeEmbedder(floatArrayOf(0f, 0f, 1f)), PriorPolicy(PriorMode.GPS),
            nanoTime = clock(0, 0, 0))
        val r = p.process(1_000, ByteArray(12), 2, 2, gps = null, preMs = 0.0)
        assertNull(r.prior)
        assertNull(r.gps)
        assertEquals(2, r.top.first().i)
    }

    @Test fun visualModeUsesPreviousFixAsWindowCenter() {
        val p = LocalizationPipeline(pack, FakeEmbedder(floatArrayOf(0f, 0f, 1f)), PriorPolicy(PriorMode.VISUAL),
            nanoTime = clock(0, 0, 0, 0, 0, 0))
        p.process(1_000, ByteArray(12), 2, 2, GpsFix(55.7518, 37.6, 4f, 990), 0.0)
        val second = p.process(2_000, ByteArray(12), 2, 2, gps = null, preMs = 0.0)
        val prior = assertNotNull(second.prior)
        assertEquals(55.7518, prior.lat)
        assertEquals(530.0, prior.radiusM, 1e-9) // 500 м + 30 м/с × 1 с
    }
}
