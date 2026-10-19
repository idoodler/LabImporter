package dev.idoodler.labimporter.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.idoodler.labimporter.LabImporterApplication
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.ParseEngine
import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.parse.ParseProgress
import dev.idoodler.labimporter.data.HealthConnectAvailability
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import java.io.File
import kotlin.time.Clock

/** A report being reviewed before it's saved (new import or edit). */
data class ReviewDraft(
    val values: List<LabValue>,
    val date: LocalDate,
    val patientName: String,
    val authorName: String,
    val engine: ParseEngine?,
    /** Set when editing an already-saved report. */
    val original: LabReport? = null,
)

sealed interface ImportState {
    data object Idle : ImportState
    data object ReadingDocument : ImportState
    data class Parsing(val progress: ParseProgress) : ImportState
    data class Reviewing(val draft: ReviewDraft) : ImportState
    data class Failed(val message: String) : ImportState
}

/** Health Connect status as shown in Settings/onboarding. */
data class HealthConnectState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.NOT_INSTALLED,
    val granted: Boolean = false,
    val inUse: Boolean = false,
)

/**
 * Orchestrates the whole import flow (document → text → AI parse → review →
 * save) and exposes saved reports — the Android counterpart of `HomeView` +
 * `LabImportEngine`.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LabImporterApplication).container
    private val repository = container.repository
    private val parser = container.parser
    private val preferences = container.preferences
    private val context: Context get() = getApplication()

    val reports: StateFlow<List<LabReport>> = repository.reports
    val isLoading: StateFlow<Boolean> = repository.isLoading
    val displayPreferences: StateFlow<LabDisplayPreferences> = preferences.displayPreferences
    val hasSeenWelcome: StateFlow<Boolean> = preferences.hasSeenWelcome
    val patientName: StateFlow<String> = preferences.patientName
    val aiStatus = parser.status
    val healthConnectPermissions: Set<String> = container.healthConnect.permissions
    val softwareIdentity: String = container.softwareIdentity

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _healthConnect = MutableStateFlow(HealthConnectState())
    val healthConnect: StateFlow<HealthConnectState> = _healthConnect.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot snackbar text. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private var importJob: Job? = null

    init {
        viewModelScope.launch { parser.refreshStatus() }
        refresh()
    }

    /** Reload reports + statuses (launch, resume, after permission changes). */
    fun refresh() {
        viewModelScope.launch {
            refreshHealthConnect()
            repository.reload()
        }
    }

    private suspend fun refreshHealthConnect() {
        val hc = container.healthConnect
        val availability = hc.availability()
        val granted = availability == HealthConnectAvailability.AVAILABLE && hc.hasPermissions()
        _healthConnect.value = HealthConnectState(availability, granted, repository.isUsingHealthConnect())
    }

    fun consumeMessage() { _message.value = null }

    // region Onboarding & settings

    fun finishOnboarding() = preferences.setHasSeenWelcome(true)

    fun setPatientName(name: String) = preferences.setPatientName(name)

    fun updateDisplayPreferences(transform: (LabDisplayPreferences) -> LabDisplayPreferences) =
        preferences.updateDisplayPreferences(transform)

    /** Called with the Health Connect permission result. */
    fun onHealthConnectPermissionsResult(granted: Set<String>) {
        viewModelScope.launch {
            if (granted.containsAll(healthConnectPermissions)) {
                val moved = runCatching { repository.enableHealthConnect() }.getOrElse {
                    _message.value = it.message
                    0
                }
                if (moved > 0) _message.value = context.resources.getQuantityString(R.plurals.hc_moved_reports, moved, moved)
            }
            refreshHealthConnect()
        }
    }

    fun setUseHealthConnect(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { if (enabled) repository.enableHealthConnect() else repository.useLocalStorage() }
                .onFailure { _message.value = it.message }
            refreshHealthConnect()
        }
    }

    fun downloadModel() {
        viewModelScope.launch { parser.download() }
    }

    // endregion

    // region Import flow

    fun prewarm() {
        viewModelScope.launch { parser.prewarm() }
    }

    fun importDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _importState.value = ImportState.ReadingDocument
            val text = runCatching { container.textExtractor.extract(uris) }.getOrElse {
                _importState.value = ImportState.Failed(it.message ?: context.getString(R.string.error_read_document))
                return@launch
            }
            parse(text)
        }
    }

    fun importText(text: String) {
        importJob?.cancel()
        importJob = viewModelScope.launch { parse(text) }
    }

    private suspend fun parse(text: String) {
        if (text.isBlank()) {
            _importState.value = ImportState.Failed(context.getString(R.string.error_no_text))
            return
        }
        _importState.value = ImportState.Parsing(ParseProgress(0, null))
        val outcome = runCatching {
            parser.parse(text) { progress -> _importState.value = ImportState.Parsing(progress) }
        }.getOrElse {
            _importState.value = ImportState.Failed(it.message ?: context.getString(R.string.error_parse))
            return
        }
        if (outcome.result.values.isEmpty()) {
            _importState.value = ImportState.Failed(context.getString(R.string.error_no_values))
            return
        }
        _importState.value = ImportState.Reviewing(
            ReviewDraft(
                values = outcome.result.values,
                date = outcome.result.reportDate ?: today(),
                patientName = outcome.result.patientName ?: preferences.patientName.value,
                authorName = outcome.result.authorName.orEmpty(),
                engine = outcome.engine,
            ),
        )
    }

    fun startManualEntry() {
        _importState.value = ImportState.Reviewing(
            ReviewDraft(
                values = listOf(blankValue()),
                date = today(),
                patientName = preferences.patientName.value,
                authorName = "",
                engine = null,
            ),
        )
    }

    fun editReport(report: LabReport) {
        _importState.value = ImportState.Reviewing(
            ReviewDraft(report.asLabValues, report.date, report.patientName, report.authorName, null, original = report),
        )
    }

    fun updateDraft(transform: (ReviewDraft) -> ReviewDraft) {
        val state = _importState.value as? ImportState.Reviewing ?: return
        _importState.value = ImportState.Reviewing(transform(state.draft))
    }

    fun updateValue(id: String, transform: (LabValue) -> LabValue) =
        updateDraft { draft -> draft.copy(values = draft.values.map { if (it.id == id) transform(it) else it }) }

    fun addValue(): String {
        val value = blankValue()
        updateDraft { it.copy(values = it.values + value) }
        return value.id
    }

    fun removeValue(id: String) = updateDraft { draft -> draft.copy(values = draft.values.filterNot { it.id == id }) }

    fun cancelImport() {
        importJob?.cancel()
        _importState.value = ImportState.Idle
    }

    fun saveDraft() {
        val draft = (_importState.value as? ImportState.Reviewing)?.draft ?: return
        viewModelScope.launch {
            val report = LabReport.fromValues(
                values = draft.values,
                date = draft.date,
                patientName = draft.patientName,
                authorName = draft.authorName,
                id = draft.original?.id ?: kotlin.uuid.Uuid.random().toString(),
            )
            runCatching {
                if (draft.original != null) repository.replace(draft.original, report) else repository.save(report)
            }.onSuccess {
                _importState.value = ImportState.Idle
                _message.value = context.resources.getQuantityString(R.plurals.saved_values, report.entries.size, report.entries.size)
            }.onFailure {
                _message.value = context.getString(R.string.error_save, it.message.orEmpty())
            }
        }
    }

    // endregion

    fun deleteReport(report: LabReport) {
        viewModelScope.launch {
            runCatching { repository.delete(report) }
                .onSuccess { _message.value = context.getString(R.string.report_deleted) }
                .onFailure { _message.value = it.message }
        }
    }

    /** Writes the report as a C-CDA document (shared generator) and returns a shareable Uri. */
    fun exportCda(report: LabReport): Uri {
        val xml = CdaExport.generate(report.asLabValues, report.date, softwareIdentity, report.patientName, report.authorName)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "LabResults-${report.date}.xml").apply { writeText(xml) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun blankValue() = LabValue(code = "", name = "", displayValue = "", numericValue = null, unit = "")

    private fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
}
