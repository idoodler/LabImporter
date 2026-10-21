package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.core.model.SortEditorState
import kotlin.test.Test
import kotlin.test.assertEquals

class SortEditorStateTest {
    private val all = listOf("a", "b", "c", "d")

    @Test
    fun startsFromTheSavedOrderThenFirstSeen() {
        val prefs = LabDisplayPreferences(orderedCodes = listOf("c", "gone", "a"), pinnedCodes = listOf("b"), hiddenCodes = listOf("d"))
        val state = SortEditorState.from(prefs, all)
        assertEquals(listOf("b"), state.pinnedCodes)
        assertEquals(listOf("c", "a"), state.unpinnedCodes)
        assertEquals(listOf("d"), state.hiddenCodes)
    }

    @Test
    fun pinningMovesARowBetweenSectionsInPlace() {
        val state = SortEditorState.from(LabDisplayPreferences(), all).togglePin("c")
        assertEquals(listOf("c"), state.pinnedCodes)
        assertEquals(listOf("a", "b", "d"), state.unpinnedCodes)
        assertEquals(listOf("c", "a", "b", "d"), state.visible)
        // Unpinned again, it heads the visible section — where it sat, just below the pinned ones.
        assertEquals(listOf("c", "a", "b", "d"), state.togglePin("c").unpinnedCodes)
    }

    @Test
    fun hideAndRestore() {
        val hidden = SortEditorState.from(LabDisplayPreferences(), all).hide("b")
        assertEquals(listOf("a", "c", "d"), hidden.unpinnedCodes)
        assertEquals(listOf("b"), hidden.hiddenCodes)
        val restored = hidden.restore("b")
        assertEquals(listOf("a", "c", "d", "b"), restored.unpinnedCodes)
        assertEquals(emptyList(), restored.hiddenCodes)
    }

    @Test
    fun movesStayWithinTheirSection() {
        val state = SortEditorState.from(LabDisplayPreferences(pinnedCodes = listOf("a", "b")), all)
        // SwiftUI semantics: moving "c" below "d" is from 0 to 2.
        assertEquals(listOf("d", "c"), state.move(inPinned = false, from = 0, to = 2).unpinnedCodes)
        assertEquals(listOf("b", "a"), state.move(inPinned = true, from = 1, to = 0).pinnedCodes)
        assertEquals(listOf("b", "a"), state.moveBy("a", 1).pinnedCodes)
        assertEquals(listOf("d", "c"), state.moveBy("d", -1).unpinnedCodes)
        // Ends don't move, and an up/down never crosses into the other section.
        assertEquals(state, state.moveBy("c", -1))
        assertEquals(state, state.moveBy("b", 1))
    }

    @Test
    fun savingKeepsNicknamesRangesAndAbsentCodes() {
        val prefs = LabDisplayPreferences(
            pinnedCodes = listOf("zz"), hiddenCodes = listOf("yy"),
            nicknames = mapOf("a" to "Alpha"), referenceRanges = mapOf("a" to ReferenceRange(1.0, 2.0)),
        )
        val saved = SortEditorState.from(prefs, all).togglePin("b").hide("c").applyTo(prefs)
        assertEquals(listOf("b", "a", "d", "c"), saved.orderedCodes)
        assertEquals(listOf("b", "zz"), saved.pinnedCodes)
        assertEquals(listOf("c", "yy"), saved.hiddenCodes)
        assertEquals(prefs.nicknames, saved.nicknames)
        assertEquals(prefs.referenceRanges, saved.referenceRanges)
    }
}
