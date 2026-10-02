package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GnssMonitorTest {
    private fun fix(t: Double, lat: Double = 55.75, lon: Double = 37.6, acc: Float = 4f) =
        LocEvent(t, lat, lon, acc, 10f, 0.5f, 90f, 2f)
    private fun status(t: Double, used: Int = 14, cn0: Float = 35f, std: Float = 5f) =
        GnssStatusEvent(t, 30, used, cn0, std, cn0 + 9f, 6, 4, 3, 1)
    private fun GnssMonitor.goodSecond(t: Double) { onStatus(status(t)); onFix(fix(t), 1.0, 2.0, 4.0) }
    private fun GnssMonitor.latchAt(t0: Double) {
        for (i in 0..2) onFix(fix(t0 + i * 1_000.0), 50.0, 200.0, 5.0)
    }

    @Test fun cleanSignalIsGood() {
        val m = GnssMonitor(); m.goodSecond(0.0)
        assertEquals(Assessment(GnssHealth.GOOD, emptySet()), m.assess(500.0))
    }

    @Test fun fixGapDegradesThenNoFixIsUntrusted() {
        val m = GnssMonitor(); m.goodSecond(0.0)
        assertEquals(Assessment(GnssHealth.DEGRADED, setOf(GnssReason.FIX_GAP)), m.assess(3_100.0))
        val a = m.assess(10_100.0)
        assertEquals(GnssHealth.UNTRUSTED, a.health); assertTrue(GnssReason.NO_FIX in a.reasons)
    }

    @Test fun fixGapAndNoFixNearThresholdsAreClean() {
        val m = GnssMonitor(); m.goodSecond(0.0)
        assertEquals(GnssHealth.GOOD, m.assess(2_900.0).health) // чуть меньше fixGapMs
        val a = m.assess(9_900.0) // чуть меньше noFixMs
        assertFalse(GnssReason.NO_FIX in a.reasons)
        assertTrue(GnssReason.FIX_GAP in a.reasons)
    }

    @Test fun satelliteAndCn0Boundaries() {
        val ok = GnssMonitor(); ok.onFix(fix(0.0), 1.0, 1.0, 4.0); ok.onStatus(status(0.0, used = 5, cn0 = 25f))
        assertEquals(GnssHealth.GOOD, ok.assess(100.0).health)
        val bad = GnssMonitor(); bad.onFix(fix(0.0), 1.0, 1.0, 4.0); bad.onStatus(status(0.0, used = 4, cn0 = 24.9f))
        assertEquals(setOf(GnssReason.FEW_SATS, GnssReason.LOW_CN0), bad.assess(100.0).reasons)
    }

    @Test fun staleStatusIsIgnored() {
        val m = GnssMonitor()
        m.onStatus(status(0.0, used = 3)); m.onFix(fix(9_000.0), 1.0, 1.0, 4.0)
        assertEquals(GnssHealth.GOOD, m.assess(9_100.0).health)
    }

    @Test fun uniformCn0NeedsThreeOfFive() {
        val m = GnssMonitor()
        for (i in 0..4) m.onStatus(status(i * 1_000.0, used = 12, cn0 = 40f, std = 3f))
        for (i in 5..6) m.onStatus(status(i * 1_000.0, used = 12, cn0 = 40f, std = 0.8f))
        m.onFix(fix(6_000.0), 1.0, 1.0, 4.0)
        assertFalse(GnssReason.UNIFORM_CN0 in m.assess(6_100.0).reasons) // 2 из 5
        m.onStatus(status(7_000.0, used = 12, cn0 = 40f, std = 0.8f)); m.onFix(fix(7_000.0), 1.0, 1.0, 4.0)
        val a = m.assess(7_100.0)
        assertEquals(GnssHealth.UNTRUSTED, a.health); assertTrue(GnssReason.UNIFORM_CN0 in a.reasons)
    }

    @Test fun uniformCn0RequiresMinUsedSatellites() {
        val m = GnssMonitor()
        for (i in 0..2) m.onStatus(status(i * 1_000.0, used = 5, cn0 = 40f, std = 0.8f))
        m.onFix(fix(2_000.0), 1.0, 1.0, 4.0)
        assertFalse(GnssReason.UNIFORM_CN0 in m.assess(2_100.0).reasons) // used < uniformMinUsed(6)
    }

    @Test fun jumpAccountsForFixAccuracy() {
        fun jumped(dLatDeg: Double, acc2: Float): Boolean {
            val m = GnssMonitor()
            m.onFix(fix(0.0), null, null, null)
            m.onFix(fix(1_000.0, lat = 55.75 + dLatDeg, acc = acc2), null, null, null)
            return GnssReason.JUMP in m.assess(1_100.0).reasons
        }
        assertTrue(jumped(0.00135, 4f))    // ~150 м > 70 + 3·8 = 94
        assertFalse(jumped(0.00081, 4f))   // ~90 м
        assertFalse(jumped(0.00135, 30f))  // ~150 м < 70 + 3·34 = 172
    }

    @Test fun outOfOrderFixIsIgnored() {
        val m = GnssMonitor()
        m.onFix(fix(2_000.0), null, null, null)
        m.onFix(fix(1_000.0, lat = 55.80), null, null, null) // старше последнего фикса: игнорируется целиком
        assertFalse(GnssReason.JUMP in m.assess(2_100.0).reasons)
        val gap = m.assess(5_100.0) // разрыв отсчитывается от t=2000, а не от игнорированного t=1000
        assertFalse(GnssReason.NO_FIX in gap.reasons)
        assertTrue(GnssReason.FIX_GAP in gap.reasons)
        m.onFix(fix(3_000.0), null, null, null) // тот же адрес, что и t=2000 — не скачок
        assertFalse(GnssReason.JUMP in m.assess(3_100.0).reasons)
    }

    @Test fun jumpHoldsForConfiguredWindowThenClears() {
        val m = GnssMonitor()
        m.onFix(fix(0.0), null, null, null)
        m.onFix(fix(1_000.0, lat = 55.80), null, null, null) // 5.5 км за 1 с — скачок, держится до t=11000
        m.onFix(fix(2_000.0, lat = 55.80), null, null, null) // не новый скачок (то же место)
        assertTrue(GnssReason.JUMP in m.assess(10_900.0).reasons)
        m.onFix(fix(11_500.0, lat = 55.80), null, null, null) // свежий фикс, тоже не скачок
        assertFalse(GnssReason.JUMP in m.assess(11_600.0).reasons)
    }

    @Test fun innovationLatchNeedsConsecutiveBadFixes() {
        val m = GnssMonitor()
        m.onFix(fix(0.0), 50.0, 100.0, 5.0); m.onFix(fix(1_000.0), null, null, null)
        m.onFix(fix(2_000.0), 50.0, 100.0, 5.0); m.onFix(fix(3_000.0), 50.0, 100.0, 5.0)
        assertFalse(GnssReason.INNOVATION in m.assess(3_100.0).reasons) // null разорвал серию
        m.onFix(fix(4_000.0), 50.0, 100.0, 5.0)
        assertEquals(GnssHealth.UNTRUSTED, m.assess(4_100.0).health)
    }

    @Test fun latchNeedsDistanceOffsetToo() {
        val m = GnssMonitor()
        for (i in 0..2) m.onFix(fix(i * 1_000.0), 50.0, 20.0, 5.0) // d² плохой, но dist в норме
        assertFalse(GnssReason.INNOVATION in m.assess(2_100.0).reasons)
    }

    @Test fun relocksWhenFilterIsTrustworthyAndClose() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..13) m.onFix(fix(i * 1_000.0), 1.0, 10.0, 8.0)
        assertFalse(GnssReason.INNOVATION in m.assess(13_100.0).reasons)
        assertFalse(m.consumeReinit())
    }

    @Test fun smallD2FromGrownSigmaDoesNotRelock() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..60) m.onFix(fix(i * 1_000.0), 1.0, 100.0, 60.0) // «согласуется» только из-за большой σ
        assertTrue(GnssReason.INNOVATION in m.assess(60_100.0).reasons)
        assertFalse(m.consumeReinit())
    }

    @Test fun visualAgreementRelocksAndRequestsReinit() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..13) {
            val t = i * 1_000.0
            m.onVisualFix(t - 200.0, 55.7501, 37.6, 8.0)       // ~11 м от GNSS
            m.onFix(fix(t), 30.0, 120.0, 80.0)                  // фильтр уплыл
        }
        assertFalse(GnssReason.INNOVATION in m.assess(13_100.0).reasons)
        assertTrue(m.consumeReinit()); assertFalse(m.consumeReinit())
    }

    @Test fun visualDisagreementKeepsLatch() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..30) {
            val t = i * 1_000.0
            m.onVisualFix(t - 200.0, 55.752, 37.6, 8.0)        // ~220 м от GNSS
            m.onFix(fix(t), 30.0, 120.0, 80.0)
        }
        assertTrue(GnssReason.INNOVATION in m.assess(30_100.0).reasons)
    }

    @Test fun oneFixWithoutEvidenceResetsRelockStreak() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..12) m.onFix(fix(i * 1_000.0), 1.0, 10.0, 8.0)   // 9 с хорошего доказательства (okSince=3000)
        m.onFix(fix(13_000.0), 30.0, 200.0, 80.0)                     // без доказательств — отсчёт сбрасывается
        for (i in 14..23) m.onFix(fix(i * 1_000.0), 1.0, 10.0, 8.0)  // ещё 9 с, но отсчёт начат заново
        assertTrue(GnssReason.INNOVATION in m.assess(23_100.0).reasons)
        assertFalse(m.consumeReinit())
    }

    @Test fun oneVisualOnlyFixInStreakStillRequestsReinit() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..7) m.onFix(fix(i * 1_000.0), 1.0, 10.0, 8.0)   // доказательство фильтром, t=3000..7000
        m.onVisualFix(7_900.0, 55.7501, 37.6, 8.0)                   // ~11 м от GNSS на t=8000
        m.onFix(fix(8_000.0), 30.0, 120.0, 80.0)                     // доказательство только визуальное
        for (i in 9..13) m.onFix(fix(i * 1_000.0), 1.0, 10.0, 8.0)  // снова фильтром, до t=13000 (10 с всего)
        assertFalse(GnssReason.INNOVATION in m.assess(13_100.0).reasons)
        assertTrue(m.consumeReinit()); assertFalse(m.consumeReinit())
    }

    @Test fun newLatchClearsUnconsumedReinit() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..13) {
            val t = i * 1_000.0
            m.onVisualFix(t - 200.0, 55.7501, 37.6, 8.0)
            m.onFix(fix(t), 30.0, 120.0, 80.0)
        }
        assertFalse(GnssReason.INNOVATION in m.assess(13_100.0).reasons) // снят по визуальному согласию
        m.latchAt(14_000.0) // новая фиксация до того, как reinit был востребован
        assertTrue(GnssReason.INNOVATION in m.assess(16_100.0).reasons)
        assertFalse(m.consumeReinit()) // запрос сброшен новой фиксацией
    }

    @Test fun staleVisualFixIsNotEvidence() {
        val m = GnssMonitor(); m.latchAt(0.0)
        m.onVisualFix(500.0, 55.7501, 37.6, 8.0) // устареет к visualAgreeMs=2000 для последующих фиксов
        for (i in 3..13) m.onFix(fix(i * 1_000.0), 30.0, 120.0, 80.0) // фильтр всегда неточен
        assertTrue(GnssReason.INNOVATION in m.assess(13_100.0).reasons)
    }

    @Test fun visualSigmaWidensAgreementRadius() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..13) {
            val t = i * 1_000.0
            m.onVisualFix(t - 100.0, 55.75 + 0.0004, 37.6, 25.0) // ~44 м, σ=25 → допуск max(30,50)=50
            m.onFix(fix(t), 30.0, 120.0, 80.0)
        }
        assertFalse(GnssReason.INNOVATION in m.assess(13_100.0).reasons)
        assertTrue(m.consumeReinit())
    }

    @Test fun filterEvidenceNeedsBothSigmaAndDistance() {
        val sigmaOkDistBad = GnssMonitor(); sigmaOkDistBad.latchAt(0.0)
        for (i in 3..13) sigmaOkDistBad.onFix(fix(i * 1_000.0), 1.0, 100.0, 20.0) // σ ок, dist плохой
        assertTrue(GnssReason.INNOVATION in sigmaOkDistBad.assess(13_100.0).reasons)

        val distOkSigmaBad = GnssMonitor(); distOkSigmaBad.latchAt(0.0)
        for (i in 3..13) distOkSigmaBad.onFix(fix(i * 1_000.0), 1.0, 20.0, 60.0) // dist ок, σ плохая
        assertTrue(GnssReason.INNOVATION in distOkSigmaBad.assess(13_100.0).reasons)
    }

    @Test fun agcDropDegrades() {
        val m = GnssMonitor()
        for (i in 0..20) { val t = i * 1_000.0; m.goodSecond(t); m.onAgc(AgcEvent(t, 2f, 10)) }
        m.goodSecond(21_000.0); m.onAgc(AgcEvent(21_000.0, -5f, 10))
        assertEquals(Assessment(GnssHealth.DEGRADED, setOf(GnssReason.AGC_DROP)), m.assess(21_100.0))
    }

    @Test fun slowAgcRampIsNotAbsorbed() {
        val m = GnssMonitor()
        for (i in 0..20) { val t = i * 1_000.0; m.goodSecond(t); m.onAgc(AgcEvent(t, 2f, 10)) }
        for (k in 1..120) {
            val t = (20 + k) * 1_000.0
            m.goodSecond(t); m.onAgc(AgcEvent(t, 2f - 0.1f * k, 10))
        }
        assertTrue(GnssReason.AGC_DROP in m.assess(140_100.0).reasons)
    }

    @Test fun persistentAgcShiftIsRelearned() {
        val m = GnssMonitor()
        for (i in 0..20) { val t = i * 1_000.0; m.goodSecond(t); m.onAgc(AgcEvent(t, 2f, 10)) }
        for (k in 1..305) { val t = (20 + k) * 1_000.0; m.goodSecond(t); m.onAgc(AgcEvent(t, -8f, 10)) }
        assertFalse(GnssReason.AGC_DROP in m.assess(325_100.0).reasons)
    }

    @Test fun poorAccuracyDegrades() {
        val m = GnssMonitor(); m.onFix(fix(0.0, acc = 35f), 1.0, 1.0, 4.0)
        assertEquals(Assessment(GnssHealth.DEGRADED, setOf(GnssReason.POOR_ACCURACY)), m.assess(100.0))
    }
}
