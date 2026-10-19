package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.loinc.LoincDirectory
import dev.idoodler.labimporter.core.model.LabDisplayPreferences

/**
 * Process-wide entry point for the shared business logic. Each app calls
 * [start] once at launch with the on-disk path of the bundled `loinc.db` and a
 * provider for the current display preferences (backed by `UserDefaults` on
 * iOS and `SharedPreferences` on Android).
 *
 * Before [start] runs (e.g. in UI previews) every lookup degrades gracefully:
 * names fall back to the raw code and nothing is considered exportable.
 */
object LabCore {
    var loinc: LoincDirectory? = null
        private set

    private var preferencesProvider: () -> LabDisplayPreferences = { LabDisplayPreferences() }

    val preferences: LabDisplayPreferences get() = preferencesProvider()

    fun start(databasePath: String, appLanguage: String, preferences: () -> LabDisplayPreferences) {
        loinc?.close()
        loinc = LoincDirectory(databasePath, appLanguage)
        preferencesProvider = preferences
    }
}
