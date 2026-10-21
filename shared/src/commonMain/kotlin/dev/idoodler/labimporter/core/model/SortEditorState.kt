package dev.idoodler.labimporter.core.model

/**
 * The Sort & Visibility editor (iOS `LabSortEditor`, Android
 * `SortEditorScreen`): every code the user has data for, split into pinned,
 * visible and hidden, plus the edits on that split. Immutable — each edit
 * returns the new state; [applyTo] writes it back to the preferences.
 *
 * @property all every code with a reading, first-seen order (`Dashboard.codeNames`).
 * @property visible the visible codes in dashboard order, pinned first.
 * @property pinned pinned codes (may include hidden or absent ones, which keep their pin).
 * @property hidden hidden codes (may include codes without current data).
 */
data class SortEditorState(
    val all: List<String>,
    val visible: List<String>,
    val pinned: Set<String>,
    val hidden: Set<String>,
) {
    /** The "Pinned" section, in order. */
    val pinnedCodes: List<String> get() = visible.filter { it in pinned }

    /** The "Visible" section, in order. */
    val unpinnedCodes: List<String> get() = visible.filter { it !in pinned }

    /** The "Hidden" section: hidden codes with data, first-seen order. */
    val hiddenCodes: List<String> get() = all.filter { it in hidden }

    fun togglePin(code: String): SortEditorState =
        copy(pinned = if (code in pinned) pinned - code else pinned + code).normalized()

    fun hide(code: String): SortEditorState = copy(visible = visible - code, hidden = hidden + code)

    /** Brings a hidden code back, at the end of its section. */
    fun restore(code: String): SortEditorState =
        copy(visible = visible - code + code, hidden = hidden - code).normalized()

    /**
     * Moves the row at [from] to [to] within the pinned ([inPinned]) or the
     * visible section. [to] is the destination offset *before* removal — the
     * semantics of SwiftUI's `onMove` (moving down by one is `from + 2`).
     */
    fun move(inPinned: Boolean, from: Int, to: Int): SortEditorState {
        val section = (if (inPinned) pinnedCodes else unpinnedCodes).toMutableList()
        if (from !in section.indices || to !in 0..section.size) return this
        val code = section.removeAt(from)
        section.add(if (to > from) to - 1 else to, code)
        return copy(visible = if (inPinned) section + unpinnedCodes else pinnedCodes + section)
    }

    /** Moves [code] one row up (-1) or down (+1) within its section; no-op at the ends. */
    fun moveBy(code: String, delta: Int): SortEditorState {
        val inPinned = code in pinned
        val section = if (inPinned) pinnedCodes else unpinnedCodes
        val from = section.indexOf(code)
        val target = from + delta
        if (from < 0 || target !in section.indices) return this
        return move(inPinned, from, if (delta > 0) target + 1 else target)
    }

    /** The preferences with this order, pins and hidden codes; nicknames and ranges untouched. */
    fun applyTo(preferences: LabDisplayPreferences): LabDisplayPreferences = preferences.copy(
        orderedCodes = visible + hiddenCodes,
        pinnedCodes = (all.filter { it in pinned } + pinned.filter { it !in all }.sorted()),
        hiddenCodes = (hiddenCodes + hidden.filter { it !in all }.sorted()),
    )

    /** Keeps the backing order pinned-first, so a toggled row moves between sections in place. */
    private fun normalized(): SortEditorState = copy(visible = pinnedCodes + unpinnedCodes)

    companion object {
        /** The editor's starting point: the saved order, then any code not ordered yet. */
        fun from(preferences: LabDisplayPreferences, all: List<String>): SortEditorState {
            val hidden = preferences.hiddenSet
            val present = all.toSet()
            val ordered = (preferences.orderedCodes.filter { it in present } + all).distinct().filter { it !in hidden }
            return SortEditorState(all.distinct(), ordered, preferences.pinnedSet, hidden).normalized()
        }
    }
}
