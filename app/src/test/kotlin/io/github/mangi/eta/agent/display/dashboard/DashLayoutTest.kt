package io.github.mangi.eta.agent.display.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashLayoutTest {

    @Test
    fun microWindowDropsAllButtons() {
        assertEquals(DashTier.MICRO, dashTierFor(96f))
        assertEquals(DashTier.MICRO, dashTierFor(131.9f))
    }

    @Test
    fun compactWindowKeepsTwoEqualButtons() {
        assertEquals(DashTier.COMPACT, dashTierFor(132f))
        assertEquals(DashTier.COMPACT, dashTierFor(160f))
        assertEquals(DashTier.COMPACT, dashTierFor(207.9f))
    }

    @Test
    fun regularWindowShowsFullBar() {
        assertEquals(DashTier.REGULAR, dashTierFor(208f))
        assertEquals(DashTier.REGULAR, dashTierFor(420f))
    }
}
