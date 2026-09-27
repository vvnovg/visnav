package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriorTest {
    private val gps = GpsFix(55.75, 37.6, 5f, 1_000)

    @Test fun gpsModeFollowsGps() {
        val p = PriorPolicy(PriorMode.GPS)
        assertNull(p.prior(1_000))
        p.onGps(gps)
        p.onVisualFix(Fix(55.76, 37.61, 0.9f, 1_500))
        assertEquals(Prior(55.75, 37.6, 500.0), p.prior(2_000))
    }

    @Test fun visualModeBootstrapsFromGpsThenTracksAcceptedFixes() {
        val p = PriorPolicy(PriorMode.VISUAL)
        p.onGps(gps)
        assertEquals(Prior(55.75, 37.6, 500.0), p.prior(1_000))
        p.onVisualFix(Fix(55.76, 37.61, 0.3f, 1_500)) // ниже acceptSim — игнорируется
        assertEquals(Prior(55.75, 37.6, 500.0), p.prior(1_500))
        p.onVisualFix(Fix(55.76, 37.61, 0.9f, 2_000))
        p.onGps(GpsFix(10.0, 10.0, 5f, 2_500)) // после первой фиксации GPS больше не используется
        assertEquals(Prior(55.76, 37.61, 500.0), p.prior(2_000))
    }

    @Test fun visualRadiusGrowsWithFixAgeAndIsCapped() {
        val p = PriorPolicy(PriorMode.VISUAL)
        p.onVisualFix(Fix(55.76, 37.61, 0.9f, 0))
        assertEquals(500.0 + 30.0 * 10, p.prior(10_000)!!.radiusM, 1e-9)
        assertEquals(3000.0, p.prior(1_000_000)!!.radiusM, 1e-9)
    }
}
