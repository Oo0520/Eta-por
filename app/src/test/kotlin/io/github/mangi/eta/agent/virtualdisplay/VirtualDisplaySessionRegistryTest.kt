package io.github.mangi.eta.agent.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplaySessionRegistryTest {
    @Test
    fun primarySlotKeepsUpstreamSingleSessionBehaviour() {
        val registry = VirtualDisplaySessionRegistry()
        val state = registry.primary()

        val token = state.begin()
        assertTrue(state.activate(token, 8))
        assertEquals(8, registry.activeOf(VirtualDisplaySessionRegistry.DEFAULT_OWNER)?.displayId)
        assertEquals(listOf(VirtualDisplaySessionRegistry.DEFAULT_OWNER), registry.activeOwners())
    }

    @Test
    fun eachOwnerGetsItsOwnSlot() {
        val registry = VirtualDisplaySessionRegistry()
        val a = registry.slot("conv-a")
        val b = registry.slot("conv-b")

        assertTrue(a !== b)
        assertTrue(a.activate(a.begin(), 8))
        assertTrue(b.activate(b.begin(), 9))

        assertEquals(setOf("conv-a", "conv-b"), registry.activeOwners().toSet())
        assertEquals(9, registry.activeOf("conv-b")?.displayId)
    }

    @Test
    fun ownerWithLiveDisplayCanOpenAgainEvenAtLimit() {
        val registry = VirtualDisplaySessionRegistry(maxSessions = 1)
        assertTrue(registry.slot("conv-a").activate(registry.slot("conv-a").begin(), 8))

        assertTrue(registry.canOpenNew("conv-a"))
        assertFalse(registry.canOpenNew("conv-b"))
    }

    @Test
    fun reclaimOnlyTakesIdleSlotsOfOtherOwners() {
        val registry = VirtualDisplaySessionRegistry(maxSessions = 2)
        registry.slot("conv-a").let { it.activate(it.begin(), 8) }
        registry.slot("conv-b").let { it.activate(it.begin(), 9) }

        assertNull(registry.reclaimIdle("conv-c") { false })
        assertEquals("conv-a", registry.reclaimIdle("conv-b") { owner -> owner == "conv-a" })
        assertNull(registry.activeOf("conv-a"))
        assertNotNull(registry.activeOf("conv-b"))
    }

    @Test
    fun closeOwnerDropsSlotAndMovesFocus() {
        val registry = VirtualDisplaySessionRegistry()
        val a = registry.slot("conv-a")
        assertTrue(a.activate(a.begin(), 8))
        val b = registry.slot("conv-b")
        assertTrue(b.activate(b.begin(), 9))
        assertTrue(registry.focus("conv-b"))
        assertEquals("conv-b", registry.focusedOwner)

        assertTrue(registry.closeOwner("conv-b"))
        assertEquals("conv-a", registry.focusedOwner)
        assertFalse(registry.closeOwner("conv-b"))
        assertNull(registry.activeOf("conv-b"))
    }

    @Test
    fun focusRejectsOwnerWithoutLiveDisplay() {
        val registry = VirtualDisplaySessionRegistry()
        assertFalse(registry.focus("nobody"))
        assertNull(registry.focusedOwner)
    }
}
