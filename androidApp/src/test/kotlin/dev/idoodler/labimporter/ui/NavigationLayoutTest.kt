package dev.idoodler.labimporter.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Switching between the phone stack and the tablet rail keeps the user's place. */
class NavigationLayoutTest {
    @Test
    fun theRailIsForRoomyWindowsOnly() {
        assertTrue(usesRail(1280.dp, 800.dp))
        assertFalse(usesRail(411.dp, 914.dp)) // phone, portrait
        assertFalse(usesRail(914.dp, 411.dp)) // phone, landscape: still a phone (like iOS's compact height)
    }

    @Test
    fun stacksConvertBothWays() {
        // Phone → rail: Reports and Settings become their own sections.
        assertEquals(listOf("reports", "report:r1"), stackForLayout(listOf("home", "reports", "report:r1"), rail = true))
        assertEquals(listOf("settings", "sort"), stackForLayout(listOf("home", "settings", "sort"), rail = true))
        assertEquals(listOf("home", "trend:2160-0"), stackForLayout(listOf("home", "trend:2160-0"), rail = true))
        // Rail → phone: everything is rooted at the dashboard again.
        assertEquals(listOf("home", "reports", "report:r1"), stackForLayout(listOf("reports", "report:r1"), rail = false))
        assertEquals(listOf("home", "settings"), stackForLayout(listOf("settings"), rail = false))
        assertEquals(listOf("home"), stackForLayout(emptyList(), rail = false))
        // Round trips change nothing.
        val stack = listOf("home", "reports", "pdf")
        assertEquals(stack, stackForLayout(stackForLayout(stack, rail = true), rail = false))
    }

    @Test
    fun aStackBelongsToTheSectionAtItsRoot() {
        assertEquals(Section.REPORTS, Section.of(listOf("reports", "report:r1")))
        assertEquals(Section.SETTINGS, Section.of(listOf("settings", "catalog")))
        assertEquals(Section.DASHBOARD, Section.of(listOf("home", "reports")))
    }
}
