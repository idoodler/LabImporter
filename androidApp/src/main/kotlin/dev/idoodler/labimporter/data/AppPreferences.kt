package dev.idoodler.labimporter.data

import android.content.Context
import androidx.core.content.edit
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where saved reports are written. */
enum class StorageMode { LOCAL, HEALTH_CONNECT }

/**
 * UI preferences and patient metadata — the Android counterpart of the iOS
 * `@AppStorage` values. Lab data itself never lives here. Excluded from
 * backup (see `data_extraction_rules.xml`).
 */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("labimporter", Context.MODE_PRIVATE)

    private val _displayPreferences = MutableStateFlow(
        LabDisplayPreferences.decode(prefs.getString(LabDisplayPreferences.STORAGE_KEY, null)),
    )
    val displayPreferences: StateFlow<LabDisplayPreferences> = _displayPreferences.asStateFlow()

    private val _hasSeenWelcome = MutableStateFlow(prefs.getBoolean(KEY_WELCOME, false))
    val hasSeenWelcome: StateFlow<Boolean> = _hasSeenWelcome.asStateFlow()

    private val _patientName = MutableStateFlow(prefs.getString(KEY_PATIENT, "").orEmpty())
    val patientName: StateFlow<String> = _patientName.asStateFlow()

    private val _storageMode = MutableStateFlow(
        runCatching { StorageMode.valueOf(prefs.getString(KEY_STORAGE, null).orEmpty()) }.getOrDefault(StorageMode.LOCAL),
    )
    val storageMode: StateFlow<StorageMode> = _storageMode.asStateFlow()

    fun updateDisplayPreferences(transform: (LabDisplayPreferences) -> LabDisplayPreferences) {
        val updated = transform(_displayPreferences.value)
        _displayPreferences.value = updated
        prefs.edit { putString(LabDisplayPreferences.STORAGE_KEY, updated.encode()) }
    }

    fun setHasSeenWelcome(value: Boolean) {
        _hasSeenWelcome.value = value
        prefs.edit { putBoolean(KEY_WELCOME, value) }
    }

    fun setPatientName(value: String) {
        _patientName.value = value
        prefs.edit { putString(KEY_PATIENT, value) }
    }

    fun setStorageMode(value: StorageMode) {
        _storageMode.value = value
        prefs.edit { putString(KEY_STORAGE, value.name) }
    }

    private companion object {
        const val KEY_WELCOME = "hasSeenWelcome"
        const val KEY_PATIENT = "patientName"
        const val KEY_STORAGE = "storageMode"
    }
}
