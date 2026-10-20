package dev.idoodler.labimporter.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Dashboard ordering/visibility plus per-code nicknames and reference ranges.
 *
 * Persisted as the same JSON blob the iOS app stores under `labDisplayPrefs`
 * (nicknames keep the legacy `customNames` key), so the format is shared across
 * platforms. Nicknames and ranges are cosmetic — they never reach an export.
 */
@Serializable
data class LabDisplayPreferences(
    val pinnedCodes: List<String> = emptyList(),
    val orderedCodes: List<String> = emptyList(),
    val hiddenCodes: List<String> = emptyList(),
    @SerialName("customNames") val nicknames: Map<String, String> = emptyMap(),
    val referenceRanges: Map<String, ReferenceRange> = emptyMap(),
) {
    val pinnedSet: Set<String> get() = pinnedCodes.toSet()
    val hiddenSet: Set<String> get() = hiddenCodes.toSet()

    /** The nickname for [code], or null if unset/blank. */
    fun nickname(code: String): String? =
        nicknames[code.trim()]?.trim()?.takeIf { it.isNotEmpty() }

    /** Sets, or (for null/blank) clears, the nickname for [code]. */
    fun withNickname(name: String?, code: String): LabDisplayPreferences {
        val key = code.trim()
        val trimmed = name?.trim().orEmpty()
        return copy(nicknames = if (trimmed.isEmpty()) nicknames - key else nicknames + (key to trimmed))
    }

    /** The user's range for [code], or null if none (or an empty one) is stored. */
    fun referenceRange(code: String): ReferenceRange? =
        referenceRanges[code.trim()]?.takeUnless { it.isEmpty }

    fun withReferenceRange(range: ReferenceRange?, code: String): LabDisplayPreferences {
        val key = code.trim()
        return copy(
            referenceRanges = if (range == null || range.isEmpty) referenceRanges - key else referenceRanges + (key to range),
        )
    }

    fun togglePinned(code: String): LabDisplayPreferences =
        copy(pinnedCodes = if (code in pinnedCodes) pinnedCodes - code else pinnedCodes + code)

    fun toggleHidden(code: String): LabDisplayPreferences =
        copy(hiddenCodes = if (code in hiddenCodes) hiddenCodes - code else hiddenCodes + code)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** The key the blob is stored under (matches iOS `@AppStorage`). */
        const val STORAGE_KEY = "labDisplayPrefs"

        // encodeDefaults: always write every key — older iOS builds (synced over
        // iCloud) decode the lists as required fields. explicitNulls=false: a
        // one-sided range omits the missing bound, as Swift's Codable does.
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        /** Decodes a stored blob; anything unreadable yields the defaults. */
        fun decode(raw: String?): LabDisplayPreferences {
            if (raw.isNullOrBlank()) return LabDisplayPreferences()
            return runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(LabDisplayPreferences())
        }
    }
}
