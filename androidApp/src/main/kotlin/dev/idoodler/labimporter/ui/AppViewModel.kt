package dev.idoodler.labimporter.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.idoodler.labimporter.AppContainerOwner
import dev.idoodler.labimporter.LabImporterApplication
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.AiException
import dev.idoodler.labimporter.ai.messageRes
import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.export.PdfReport
import dev.idoodler.labimporter.core.model.Dashboard
import dev.idoodler.labimporter.shortcuts.LauncherShortcuts
import dev.idoodler.labimporter.data.PatientDetails
import dev.idoodler.labimporter.export.PdfOptions
import dev.idoodler.labimporter.export.PdfReportRenderer
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.parse.ParseProgress
import dev.idoodler.labimporter.core.transfer.ReportTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.idoodler.labimporter.data.HealthConnectAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
    /** Which model read the values; null for manual entry and edits. */
    val engine: AiEngine?,
    /** Names the model read from the report — offered as suggestions, never applied unasked (as on iOS). */
    val detectedPatientName: String? = null,
    val detectedAuthorName: String? = null,
    /** Set when editing an already-saved report. */
    val original: LabReport? = null,
)

/** Something asked for from outside the app (a launcher shortcut), done once the main screen shows — like iOS's `SiriActionBridge`. */
sealed interface LaunchRequest {
    data object Scan : LaunchRequest
    data class OpenMetric(val code: String) : LaunchRequest
}

sealed interface ImportState {
    data object Idle : ImportState
    data object ReadingDocument : ImportState
    /** The model is still being downloaded; the import continues once it's ready. */
    data class PreparingModel(val preparing: AiAvailability.Preparing, val engine: AiEngine) : ImportState
    data class Parsing(val progress: ParseProgress) : ImportState
    data class Reviewing(val draft: ReviewDraft) : ImportState
    data class Failed(val message: String) : ImportState
}

/** Health Connect status as shown in Settings/onboarding. */
data class HealthConnectState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.NOT_INSTALLED,
    val granted: Boolean = false,
    val inUse: Boolean = false,
) {
    /**
     * Whether to offer moving reports here: Health Connect works but was never
     * connected (a user who connected and then chose app storage isn't asked
     * again), there's something to move, and the offer wasn't turned down.
     */
    fun offersMove(hasReports: Boolean, dismissed: Boolean): Boolean =
        availability == HealthConnectAvailability.AVAILABLE && !granted && !inUse && hasReports && !dismissed
}

/**
 * Orchestrates the whole import flow (document → text → AI parse → review →
 * save) and exposes saved reports — the Android counterpart of `HomeView` +
 * `LabImportEngine`.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AppContainerOwner).container
    private val repository = container.repository
    private val parser = container.parser
    private val ai = container.ai
    private val preferences = container.preferences
    private val context: Context get() = getApplication()

    val reports: StateFlow<List<LabReport>> = repository.reports
    val isLoading: StateFlow<Boolean> = repository.isLoading
    val displayPreferences: StateFlow<LabDisplayPreferences> = preferences.displayPreferences
    val hasSeenWelcome: StateFlow<Boolean> = preferences.hasSeenWelcome
    val patientName: StateFlow<String> = preferences.patientName
    val patientDetails: StateFlow<PatientDetails> = preferences.patientDetails
    /** Whether an on-device model can read reports; [AiAvailability.Unsupported] blocks the app like iOS. */
    val aiAvailability: StateFlow<AiAvailability> = ai.availability

    /** Debug builds: the development stand-in replaces Gemini Nano. */
    val usingStandIn: StateFlow<Boolean> = ai.isUsingStandIn
    val healthConnectPermissions: Set<String> = container.healthConnect.permissions
    val softwareIdentity: String = container.softwareIdentity
    val appInfo = container.appInfo

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _healthConnect = MutableStateFlow(HealthConnectState())
    val healthConnect: StateFlow<HealthConnectState> = _healthConnect.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot snackbar text. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private var importJob: Job? = null

    val showLatestValueInSearch: StateFlow<Boolean> = preferences.showLatestValueInSearch

    private val _launchRequest = MutableStateFlow<LaunchRequest?>(null)
    /** A shortcut's request waiting for the main screen (it waits out onboarding or a review). */
    val launchRequest: StateFlow<LaunchRequest?> = _launchRequest.asStateFlow()

    init {
        refresh()
        // Launcher shortcuts follow the dashboard: one per visible value, names only unless opted in.
        viewModelScope.launch {
            combine(reports, displayPreferences, showLatestValueInSearch) { reports, prefs, showValue ->
                Dashboard.sorted(Dashboard.metrics(reports), prefs) to showValue
            }.distinctUntilChanged()
                .onEach { (metrics, showValue) -> LauncherShortcuts.update(context, metrics, showValue) }
                .flowOn(Dispatchers.Default) // Names come from the catalog; none of this belongs on the main thread.
                .collect()
        }
    }

    fun request(launch: LaunchRequest) { _launchRequest.value = launch }

    fun consumeLaunchRequest() { _launchRequest.value = null }

    fun setShowLatestValueInSearch(value: Boolean) = preferences.setShowLatestValueInSearch(value)

    /** Reload reports + statuses (launch, resume, after permission changes). */
    fun refresh() {
        viewModelScope.launch {
            refreshHealthConnect()
            repository.reload()
        }
        // The model can become available later (AI configuration arriving, a system update).
        val availability = ai.availability.value
        if (availability is AiAvailability.Unsupported || availability is AiAvailability.PreparationFailed) {
            viewModelScope.launch { ai.refresh() }
        }
    }

    /** "Check Again" / "Try Again": asks the system once more and retries a failed model download. */
    fun checkAiAgain() {
        viewModelScope.launch { ai.tryAgain() }
    }

    /** The downloaded Gemma model is on the phone. */
    val modelInstalled: StateFlow<Boolean> = ai.gemmaInstalled

    /** Which model reads reports — follows [aiAvailability]. */
    val aiEngine: AiEngine get() = ai.engine

    /** The user's go-ahead to download the Gemma model (Wi-Fi only unless [allowMobileData]). */
    fun downloadModel(allowMobileData: Boolean) {
        viewModelScope.launch { ai.downloadModel(allowMobileData) }
    }

    /** Cancels the model download or deletes the downloaded model. */
    fun removeModel() {
        viewModelScope.launch { ai.removeModel() }
    }

    /** Debug builds: opt into (or out of) the development stand-in. */
    fun useDevelopmentStandIn(enabled: Boolean) {
        preferences.setUseDevelopmentStandIn(enabled)
        ai.useStandIn(enabled)
        if (!enabled) checkAiAgain()
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

    fun setPatientDetails(details: PatientDetails) = preferences.setPatientDetails(details)

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

    /**
     * Whether the dashboard offers to move reports into Health Connect: it became available
     * (e.g. after the update to Android 16), it isn't connected yet, there are reports kept in
     * the app, and the user hasn't said "Not Now".
     */
    val offerHealthConnect: StateFlow<Boolean> =
        combine(healthConnect, reports, preferences.healthConnectOfferDismissed) { hc, reports, dismissed ->
            hc.offersMove(hasReports = reports.isNotEmpty(), dismissed = dismissed)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun dismissHealthConnectOffer() = preferences.setHealthConnectOfferDismissed(true)

    fun setUseHealthConnect(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { if (enabled) repository.enableHealthConnect() else repository.useLocalStorage() }
                .onFailure { _message.value = it.message }
            refreshHealthConnect()
        }
    }

    // endregion

    // region Import flow

    fun prewarm() {
        viewModelScope.launch { ai.prewarm() }
    }

    fun importDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _importState.value = ImportState.ReadingDocument
            val text = try {
                container.textExtractor.extract(uris)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the document", e)
                return@launch fail(context.getString(R.string.error_read_document))
            }
            parse(text)
        }
    }

    fun importText(text: String) {
        importJob?.cancel()
        importJob = viewModelScope.launch { parse(text) }
    }

    private suspend fun parse(text: String) {
        // A share/"open with" can arrive on a cold start before the catalog is unpacked.
        LabImporterApplication.catalogReady.first { it }
        if (text.isBlank()) return fail(context.getString(R.string.error_no_text))
        if (!awaitModel()) return
        _importState.value = ImportState.Parsing(ParseProgress(0, null))
        val engine = ai.engine
        val result = try {
            parser.parse(text) { progress -> _importState.value = ImportState.Parsing(progress) }
        } catch (e: AiException) {
            return fail(context.getString(e.problem.messageRes))
        }
        if (result.values.isEmpty()) return fail(context.getString(R.string.error_no_values))
        _importState.value = ImportState.Reviewing(
            ReviewDraft(
                values = result.values,
                date = result.reportDate ?: today(),
                patientName = preferences.patientName.value,
                authorName = preferences.authorName.value,
                engine = engine,
                detectedPatientName = result.patientName?.trim()?.takeIf { it.isNotEmpty() },
                detectedAuthorName = result.authorName?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    /**
     * Waits while the model is checked or downloaded (showing the download),
     * so an import started early still completes. False — with the reason
     * shown — when the model can't be used.
     */
    private suspend fun awaitModel(): Boolean {
        if (ai.availability.value is AiAvailability.PreparationFailed) ai.refresh() // Retry the download first.
        val settled = ai.availability
            .onEach { if (it is AiAvailability.Preparing) _importState.value = ImportState.PreparingModel(it, ai.engine) }
            .first { it !is AiAvailability.Checking && it !is AiAvailability.Preparing }
        return when (settled) {
            AiAvailability.Ready -> true
            is AiAvailability.PreparationFailed -> false.also { fail(context.getString(settled.problem.messageRes)) }
            else -> false.also { fail(context.getString(R.string.ai_problem_unavailable)) }
        }
    }

    private fun fail(message: String) {
        _importState.value = ImportState.Failed(message)
    }

    fun startManualEntry() {
        _importState.value = ImportState.Reviewing(
            ReviewDraft(
                values = listOf(blankValue()),
                date = today(),
                patientName = preferences.patientName.value,
                authorName = preferences.authorName.value,
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
                // A new report's names are remembered for the next one; editing an old report leaves them.
                if (draft.original == null) {
                    preferences.setPatientName(draft.patientName.trim())
                    preferences.setAuthorName(draft.authorName.trim())
                }
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

    /** Deletes the reports with [ids] (multi-select / Delete All) and says how it went. */
    fun deleteReports(ids: Set<String>) {
        val doomed = reports.value.filter { it.id in ids }
        if (doomed.isEmpty()) return
        viewModelScope.launch {
            val failed = repository.deleteAll(doomed)
            val deleted = doomed.size - failed
            _message.value = if (failed > 0) {
                context.getString(R.string.delete_some_failed)
            } else {
                context.resources.getQuantityString(R.plurals.reports_deleted, deleted, deleted)
            }
        }
    }

    // region Transfer (iPhone ⇄ Android)

    /** Every report as the transfer file (shared `ReportTransfer`: a FHIR Bundle), ready to share. */
    fun exportAllReports(): Uri? {
        val all = reports.value.takeIf { it.isNotEmpty() } ?: return null
        val file = File(exportsDirectory(), ReportTransfer.fileName(today()))
        file.writeText(ReportTransfer.export(all, softwareIdentity))
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /**
     * Imports a transfer file — or a LabImporter CDA document from either app —
     * skipping reports that are already here, and reports what happened.
     */
    fun importReports(uri: Uri) {
        viewModelScope.launch {
            val contents = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the transfer file", e)
                null
            } ?: return@launch run { _message.value = context.getString(R.string.error_read_document) }
            val read = ReportTransfer.read(contents) as? ReportTransfer.ReadResult.Reports
                ?: return@launch run { _message.value = context.getString(R.string.transfer_unsupported) }
            val plan = ReportTransfer.plan(reports.value, read.reports)
            try {
                // Fresh ids: storage ids from the other device mean nothing here.
                repository.saveAll(plan.toImport.map { it.copy(id = kotlin.uuid.Uuid.random().toString()) })
                _message.value = context.getString(R.string.transfer_summary, plan.toImport.size, plan.alreadyPresent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = context.getString(R.string.error_save, e.message.orEmpty())
            }
        }
    }

    // region PDF export

    /**
     * Renders the PDF — pages from the shared `PdfReport`, drawn by
     * [PdfReportRenderer] — and returns it for sharing, or null if it couldn't
     * be written.
     */
    suspend fun exportPdf(options: PdfOptions): Uri? = withContext(Dispatchers.Default) {
        val today = today()
        val data = PdfReport.build(reports.value, options.selectedCodes, options.timeRange, patientName.value, today)
        // Named after the newest report, like iOS.
        val file = File(exportsDirectory(), "LabReport-${data.latestDate ?: today}.pdf")
        try {
            file.outputStream().use { PdfReportRenderer(context).render(data, options, patientDetails.value, java.time.LocalDateTime.now(), it) }
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "PDF export failed", e)
            file.delete()
            null
        }
    }

    // endregion

    private fun exportsDirectory() = File(context.cacheDir, "exports").apply { mkdirs() }

    // endregion

    /** Writes the report as a C-CDA document (shared generator) and returns a shareable Uri. */
    fun exportCda(report: LabReport): Uri {
        val xml = CdaExport.generate(report.asLabValues, report.date, softwareIdentity, report.patientName, report.authorName)
        val file = File(exportsDirectory(), "LabResults-${report.date}.xml").apply { writeText(xml) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private companion object {
        const val TAG = "AppViewModel"
    }

    private fun blankValue() = LabValue(code = "", name = "", displayValue = "", numericValue = null, unit = "")

    private fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
}
