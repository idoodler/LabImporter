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
import dev.idoodler.labimporter.core.export.CdaNarrative
import dev.idoodler.labimporter.core.export.PdfReport
import dev.idoodler.labimporter.core.export.ReportEncodingException
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
import dev.idoodler.labimporter.data.HealthConnectUnavailableException
import dev.idoodler.labimporter.data.ReplaceOutcome
import dev.idoodler.labimporter.data.ReportsLoadProblem
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import java.io.File
import java.io.IOException
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
    /** The import found no date the report prints, so [date] is only a starting point until the user changes it. */
    val dateNotFound: Boolean = false,
    /**
     * The report prints [date], but it may be misread — day and month either way, or a
     * birth date ([dev.idoodler.labimporter.core.parse.ParseResult.reportDateNeedsCheck]).
     */
    val dateToCheck: Boolean = false,
) {
    /** The user set the date; [dateNotFound] and [dateToCheck] stand until it really changes, as on iOS. */
    fun withDate(newDate: LocalDate): ReviewDraft =
        copy(date = newDate, dateNotFound = dateNotFound && newDate == date, dateToCheck = dateToCheck && newDate == date)
}

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

/** How asking for the transfer file went. */
sealed interface ReportsExport {
    data class Ready(val uri: Uri) : ReportsExport

    /** Nothing was written: the user decides whether a file without these [results] values is what they want. */
    data class LeavesOut(val results: Int) : ReportsExport

    /** No file: why is already in the snackbar. */
    data object Failed : ReportsExport
}

/** Health Connect status as shown in Settings/onboarding. */
data class HealthConnectState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.NOT_INSTALLED,
    val granted: Boolean = false,
    val inUse: Boolean = false,
    /** The user chose Health Connect. Without [inUse] that means it went away, and they need a way out. */
    val chosen: Boolean = false,
) {
    /**
     * Whether to offer moving reports here: Health Connect works but was never
     * connected (a user who connected and then chose app storage isn't asked
     * again), there's something to move, and the offer wasn't turned down.
     */
    fun offersMove(hasReports: Boolean, dismissed: Boolean): Boolean =
        availability == HealthConnectAvailability.AVAILABLE && !granted && !inUse && !chosen && hasReports && !dismissed
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

    /** The last load failed: "no reports" would be wrong, so the dashboard says that instead. */
    val loadProblem: StateFlow<ReportsLoadProblem?> = repository.loadProblem

    /** Saved reports that exist but can't be read. */
    val unreadableReports: StateFlow<Int> = repository.unreadableReports

    /** Saved reports only a newer version of the app can read. */
    val newerVersionReports: StateFlow<Int> = repository.newerVersionReports
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

    private val _isSaving = MutableStateFlow(false)

    /** A review is being saved: Save is off meanwhile, and [saveDraft] ignores a repeated tap (it would save twice). */
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /** Counts the reviews opened, so a save that ends only closes the review it saved, never a newer one. */
    private var reviewsOpened = 0

    /** One transfer import at a time: two of one file would both plan against the same list and save it twice. */
    private val transferImport = Mutex()

    val showLatestValueInSearch: StateFlow<Boolean> = preferences.showLatestValueInSearch

    private val _launchRequest = MutableStateFlow<LaunchRequest?>(null)
    /** A shortcut's request waiting for the main screen (it waits out onboarding or a review). */
    val launchRequest: StateFlow<LaunchRequest?> = _launchRequest.asStateFlow()

    init {
        removeExpiredExports()
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
            // Moving reports into Health Connect encodes them, which needs the catalog: on a cold
            // start (and after every app update, when it's copied again) it may not be open yet.
            LabImporterApplication.catalogReady.first { it }
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
        _healthConnect.value = HealthConnectState(availability, granted, repository.isUsingHealthConnect(), repository.wantsHealthConnect)
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
                // Not dropped: connectFailed tells the user, and a move cut short resumes on the next reload.
                val moved = runCatching { repository.enableHealthConnect() }.getOrElse {
                    connectFailed(it)
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
                .onFailure(::connectFailed)
            refreshHealthConnect()
        }
    }

    private fun connectFailed(error: Throwable) {
        if (error is CancellationException) throw error
        Log.w(TAG, "Couldn't connect to Health Connect", error)
        _message.value = context.getString(R.string.hc_connect_failed)
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
        openReview(
            ReviewDraft(
                values = result.values,
                date = result.reportDate ?: today(),
                patientName = preferences.patientName.value,
                authorName = preferences.authorName.value,
                engine = engine,
                detectedPatientName = result.patientName?.trim()?.takeIf { it.isNotEmpty() },
                detectedAuthorName = result.authorName?.trim()?.takeIf { it.isNotEmpty() },
                dateNotFound = result.reportDate == null,
                dateToCheck = result.reportDateNeedsCheck,
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

    /** Shows [draft] for review: a new review, not an edit of the one on screen. */
    private fun openReview(draft: ReviewDraft) {
        reviewsOpened++
        _importState.value = ImportState.Reviewing(draft)
    }

    fun startManualEntry() {
        openReview(
            ReviewDraft(
                values = listOf(blankValue()),
                date = today(),
                patientName = preferences.patientName.value,
                authorName = preferences.authorName.value,
                engine = null,
            ),
        )
    }

    /** Opens [report] for editing, unless it's read-only: saving would drop the values this build can't read. */
    fun editReport(report: LabReport) {
        if (report.isReadOnly) return
        openReview(
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
        if (_isSaving.value) return
        _isSaving.value = true
        val review = reviewsOpened
        viewModelScope.launch {
            try {
                save(draft, review)
            } finally {
                _isSaving.value = false
            }
        }
    }

    private suspend fun save(draft: ReviewDraft, review: Int) {
        val report = LabReport.fromValues(
            values = draft.values,
            date = draft.date,
            patientName = draft.patientName,
            authorName = draft.authorName,
            id = draft.original?.id ?: kotlin.uuid.Uuid.random().toString(),
        )
        runCatching {
            if (draft.original != null) {
                repository.replace(draft.original, report)
            } else {
                repository.save(report)
                ReplaceOutcome.REPLACED
            }
        }.onSuccess { outcome ->
            // A new report's names are remembered for the next one; editing an old report leaves them.
            if (draft.original == null) {
                preferences.setPatientName(draft.patientName.trim())
                preferences.setAuthorName(draft.authorName.trim())
            }
            // A report shared meanwhile may be in review or on its way: it stays.
            if (reviewsOpened == review && _importState.value is ImportState.Reviewing) _importState.value = ImportState.Idle
            _message.value = if (outcome == ReplaceOutcome.SAVED_WITH_LEFTOVERS) {
                context.getString(R.string.save_leftovers_warning)
            } else {
                context.resources.getQuantityString(R.plurals.saved_values, report.entries.size, report.entries.size)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Couldn't save the report", it)
            _message.value = context.getString(saveFailure(it))
        }
    }

    /** Why a save failed, in the user's words; the exception itself is for the log. */
    private suspend fun saveFailure(error: Throwable): Int = when {
        error is ReportEncodingException -> R.string.error_save_values
        error is HealthConnectUnavailableException || error is SecurityException -> R.string.error_save_health_connect
        repository.isUsingHealthConnect() -> R.string.error_save_health_connect
        else -> R.string.error_save_storage
    }

    // endregion

    fun deleteReport(report: LabReport) {
        viewModelScope.launch {
            runCatching { repository.delete(report) }
                .onSuccess { _message.value = context.getString(R.string.report_deleted) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "Couldn't delete the report", it)
                    _message.value = context.getString(R.string.delete_some_failed)
                }
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

    /**
     * Every report as the transfer file (shared `ReportTransfer`: a FHIR Bundle), ready to share.
     * It's never quietly smaller than what's on screen: with [allowLeavingOut] off, values that
     * can't be written stop it and come back as [ReportsExport.LeavesOut] for the user to decide.
     */
    fun exportAllReports(allowLeavingOut: Boolean = false): ReportsExport {
        val all = reports.value
        val export = ReportTransfer.export(all, softwareIdentity)
        if (export.writtenResults == 0) {
            _message.value = context.getString(R.string.transfer_export_nothing)
            return ReportsExport.Failed
        }
        if (export.leftOutResults > 0 && !allowLeavingOut) return ReportsExport.LeavesOut(export.leftOutResults)
        val file = newExportFile(ReportTransfer.fileName(today()))
        try {
            file.writeText(export.contents)
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't write the transfer file: ${e::class.simpleName}")
            file.delete()
            _message.value = context.getString(R.string.error_save_storage)
            return ReportsExport.Failed
        }
        return ReportsExport.Ready(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
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
            val parsed = ReportTransfer.read(contents)
            if (parsed is ReportTransfer.ReadResult.NewerVersion) {
                _message.value = context.getString(R.string.transfer_newer_version)
                return@launch
            }
            val read = parsed as? ReportTransfer.ReadResult.Reports
                ?: return@launch run { _message.value = context.getString(R.string.transfer_unsupported) }
            // Skipping what's already here needs the whole list. Everything imported gets fresh ids,
            // so nothing would merge the duplicates once the saved reports can be read again.
            val (plan, failed) = transferImport.withLock {
                repository.reload()
                if (repository.loadFailed.value) {
                    _message.value = context.getString(R.string.transfer_reports_unreadable)
                    return@launch
                }
                val plan = ReportTransfer.plan(reports.value, read.reports)
                plan to repository.saveAll(plan.toImport.map(::withFreshIds))
            }
            val imported = plan.toImport.size - failed
            val summary = if (failed > 0) {
                context.getString(R.string.transfer_summary_failed, imported, plan.alreadyPresent, failed)
            } else {
                context.getString(R.string.transfer_summary, imported, plan.alreadyPresent)
            }
            _message.value = listOfNotNull(
                summary,
                context.getString(R.string.transfer_newer_version_some, read.newerVersionReports).takeIf { read.newerVersionReports > 0 },
                context.getString(R.string.transfer_damaged_some, read.unreadableReports).takeIf { read.unreadableReports > 0 },
            ).joinToString(" ")
        }
    }

    /**
     * Storage ids from the file mean nothing here. Entries need fresh ones too: Health Connect
     * upserts by entry id, so a file's older version of a report would overwrite the current one.
     */
    private fun withFreshIds(report: LabReport): LabReport = report.copy(
        id = kotlin.uuid.Uuid.random().toString(),
        entries = report.entries.map { it.copy(id = kotlin.uuid.Uuid.random().toString()) },
    )

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
        val file = newExportFile("LabReport-${data.latestDate ?: today}.pdf")
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

    private fun clearExports() {
        exportsDirectory().listFiles().orEmpty().forEach { it.delete() }
    }

    /**
     * Each export is a copy of the user's health data, but nothing says when a share target is
     * done with one: a mail draft left open may read it only when sent, however often the user
     * came back here (or this process was restarted) meanwhile. So age decides, not the app's
     * own lifecycle; and it's checked on every launch and return, there being no other chance.
     */
    fun removeExpiredExports(): Job = viewModelScope.launch(Dispatchers.IO) {
        val oldestKept = System.currentTimeMillis() - EXPORT_LIFETIME_MILLIS
        exportsDirectory().listFiles().orEmpty().filter { it.lastModified() < oldestKept }.forEach { it.delete() }
    }

    /** Where to write an export. Only the latest one stays: each is a copy of the user's health data. */
    private fun newExportFile(name: String): File {
        clearExports()
        return File(exportsDirectory(), name)
    }

    // endregion

    /** Writes the report as a C-CDA document (shared generator) and returns a shareable Uri. */
    fun exportCda(report: LabReport): Uri {
        val xml = CdaExport.generate(report.asLabValues, report.date, softwareIdentity, cdaNarrative(), report.patientName, report.authorName)
        val file = newExportFile("LabResults-${report.date}.xml").apply { writeText(xml) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** The document in the language the app is showing, whose words the catalog already translates. */
    private fun cdaNarrative() = CdaNarrative(
        languageCode = context.getString(R.string.cda_language_code),
        documentTitle = context.getString(R.string.dashboard_title),
        sectionTitle = context.getString(R.string.lab_values),
        testHeader = context.getString(R.string.pdf_column_test),
        resultHeader = context.getString(R.string.pdf_column_result),
    )

    private companion object {
        const val TAG = "AppViewModel"
        const val EXPORT_LIFETIME_MILLIS = 24 * 60 * 60 * 1000L
    }

    private fun blankValue() = LabValue(code = "", name = "", displayValue = "", numericValue = null, unit = "")

    private fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
}
