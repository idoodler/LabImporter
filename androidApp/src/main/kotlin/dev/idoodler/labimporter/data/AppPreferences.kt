package dev.idoodler.labimporter.data

import android.content.Context
import androidx.core.content.edit
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate

/** Where saved reports are written. */
enum class StorageMode { LOCAL, HEALTH_CONNECT }

enum class PatientSex { FEMALE, MALE, OTHER }

/**
 * Date of birth and sex for the PDF cover. iOS reads them from Apple Health;
 * Health Connect has no such data, so the user enters them in Settings.
 */
data class PatientDetails(val dateOfBirth: LocalDate? = null, val sex: PatientSex? = null)

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

    private val _authorName = MutableStateFlow(prefs.getString(KEY_AUTHOR, "").orEmpty())
    /** The lab or doctor of the last saved report — pre-filled for the next one (iOS `authorName`). */
    val authorName: StateFlow<String> = _authorName.asStateFlow()

    private val _patientDetails = MutableStateFlow(
        PatientDetails(
            dateOfBirth = prefs.getString(KEY_DATE_OF_BIRTH, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            sex = prefs.getString(KEY_SEX, null)?.let { raw -> PatientSex.entries.firstOrNull { it.name == raw } },
        ),
    )
    val patientDetails: StateFlow<PatientDetails> = _patientDetails.asStateFlow()

    private val _showLatestValueInSearch = MutableStateFlow(prefs.getBoolean(KEY_SHOW_LATEST_VALUE, false))
    /** Off by default: launcher shortcuts and search show a value's name only (iOS `showLatestValueInSearch`). */
    val showLatestValueInSearch: StateFlow<Boolean> = _showLatestValueInSearch.asStateFlow()

    private val _storageMode = MutableStateFlow(
        runCatching { StorageMode.valueOf(prefs.getString(KEY_STORAGE, null).orEmpty()) }.getOrDefault(StorageMode.LOCAL),
    )
    val storageMode: StateFlow<StorageMode> = _storageMode.asStateFlow()

    private val _healthConnectOfferDismissed = MutableStateFlow(prefs.getBoolean(KEY_HC_OFFER_DISMISSED, false))
    /** The user answered "Not Now" to the dashboard's offer to move reports into Health Connect. */
    val healthConnectOfferDismissed: StateFlow<Boolean> = _healthConnectOfferDismissed.asStateFlow()

    /** Debug builds: the developer chose the development stand-in over Gemini Nano. */
    val useDevelopmentStandIn: Boolean get() = prefs.getBoolean(KEY_STAND_IN, false)

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

    fun setAuthorName(value: String) {
        _authorName.value = value
        prefs.edit { putString(KEY_AUTHOR, value) }
    }

    fun setPatientDetails(value: PatientDetails) {
        _patientDetails.value = value
        prefs.edit {
            putString(KEY_DATE_OF_BIRTH, value.dateOfBirth?.toString())
            putString(KEY_SEX, value.sex?.name)
        }
    }

    fun setShowLatestValueInSearch(value: Boolean) {
        _showLatestValueInSearch.value = value
        prefs.edit { putBoolean(KEY_SHOW_LATEST_VALUE, value) }
    }

    fun setStorageMode(value: StorageMode) {
        _storageMode.value = value
        prefs.edit { putString(KEY_STORAGE, value.name) }
    }

    fun setHealthConnectOfferDismissed(value: Boolean) {
        _healthConnectOfferDismissed.value = value
        prefs.edit { putBoolean(KEY_HC_OFFER_DISMISSED, value) }
    }

    fun setUseDevelopmentStandIn(value: Boolean) {
        prefs.edit { putBoolean(KEY_STAND_IN, value) }
    }

    private companion object {
        const val KEY_WELCOME = "hasSeenWelcome"
        const val KEY_PATIENT = "patientName"
        const val KEY_AUTHOR = "authorName"
        const val KEY_DATE_OF_BIRTH = "patientDateOfBirth"
        const val KEY_SEX = "patientSex"
        const val KEY_STORAGE = "storageMode"
        const val KEY_SHOW_LATEST_VALUE = "showLatestValueInSearch"
        const val KEY_STAND_IN = "useDevelopmentStandIn"
        const val KEY_HC_OFFER_DISMISSED = "healthConnectOfferDismissed"
    }
}
