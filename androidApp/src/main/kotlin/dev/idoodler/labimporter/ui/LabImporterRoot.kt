package dev.idoodler.labimporter.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import dev.idoodler.labimporter.ui.dashboard.HealthConnectOfferCard
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.idoodler.labimporter.ui.components.ModelStatusCard
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.Dashboard
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.ai.UnsupportedReason
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.ui.dashboard.DashboardScreen
import dev.idoodler.labimporter.ui.design.LocalScreenWidth
import dev.idoodler.labimporter.ui.design.windowSize
import dev.idoodler.labimporter.ui.dashboard.TrendScreen
import dev.idoodler.labimporter.ui.export.PdfExportScreen
import dev.idoodler.labimporter.ui.history.HistoryScreen
import dev.idoodler.labimporter.ui.history.ReportDetailScreen
import dev.idoodler.labimporter.ui.importflow.ImportActions
import dev.idoodler.labimporter.ui.importflow.ImportFabMenu
import dev.idoodler.labimporter.ui.importflow.PasteTextScreen
import dev.idoodler.labimporter.ui.loinc.LoincCatalogScreen
import dev.idoodler.labimporter.ui.loinc.LoincTermScreen
import dev.idoodler.labimporter.ui.importflow.ProcessingDialog
import dev.idoodler.labimporter.ui.importflow.ProcessingStage
import dev.idoodler.labimporter.ui.importflow.rememberImportActions
import dev.idoodler.labimporter.ui.onboarding.OnboardingScreen
import dev.idoodler.labimporter.ui.onboarding.UnsupportedDeviceScreen
import dev.idoodler.labimporter.ui.review.ReviewScreen
import dev.idoodler.labimporter.ui.settings.AboutSettings
import dev.idoodler.labimporter.ui.settings.AiSettings
import dev.idoodler.labimporter.ui.settings.PatientSettings
import dev.idoodler.labimporter.ui.settings.SearchSettings
import dev.idoodler.labimporter.ui.settings.SettingsScreen
import dev.idoodler.labimporter.ui.settings.SortEditorScreen
import dev.idoodler.labimporter.ui.settings.TransferSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Where the user is. The dashboard is home — like the iOS app there's no tab
 * bar: Reports and Settings open from the dashboard's top bar, and metric /
 * report details push on top. Encoded as strings so the stack survives
 * process death via `rememberSaveable`.
 */
private sealed interface Destination {
    data object Home : Destination
    data object Reports : Destination
    data object Settings : Destination
    data object PdfExport : Destination
    data object SortEditor : Destination
    data object Catalog : Destination
    data class Term(val code: String) : Destination
    data class Trend(val code: String) : Destination
    data class Report(val id: String) : Destination

    fun encode(): String = when (this) {
        Home -> "home"
        Reports -> "reports"
        Settings -> "settings"
        PdfExport -> "pdf"
        SortEditor -> "sort"
        Catalog -> "catalog"
        is Term -> "term:$code"
        is Trend -> "trend:$code"
        is Report -> "report:$id"
    }

    companion object {
        fun decode(raw: String): Destination = when {
            raw == "reports" -> Reports
            raw == "settings" -> Settings
            raw == "pdf" -> PdfExport
            raw == "sort" -> SortEditor
            raw == "catalog" -> Catalog
            raw.startsWith("term:") -> Term(raw.removePrefix("term:"))
            raw.startsWith("trend:") -> Trend(raw.removePrefix("trend:"))
            raw.startsWith("report:") -> Report(raw.removePrefix("report:"))
            else -> Home
        }
    }
}

/** App shell: onboarding, the review flow, and a back stack rooted at the dashboard. */
@Composable
fun LabImporterRoot(viewModel: AppViewModel) {
    val hasSeenWelcome by viewModel.hasSeenWelcome.collectAsStateWithLifecycle()
    val healthConnect by viewModel.healthConnect.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.onHealthConnectPermissionsResult(it)
    }
    val connectHealthConnect = { permissionLauncher.launch(viewModel.healthConnectPermissions) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // The download carries on in the background; a notification says when it's done — so ask
    // for that permission as the user starts it (the download starts either way).
    val context = LocalContext.current
    var pendingDownload by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingDownload?.let(viewModel::downloadModel)
        pendingDownload = null
    }
    val startModelDownload: (Boolean) -> Unit = { allowMobileData ->
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingDownload = allowMobileData
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.downloadModel(allowMobileData)
        }
    }

    // Like iOS: without the on-device AI there is no app, only the reason why.
    val availability by viewModel.aiAvailability.collectAsStateWithLifecycle()
    var lastUnsupported by rememberSaveable { mutableStateOf<UnsupportedReason?>(null) }
    var lastDetail by rememberSaveable { mutableStateOf<String?>(null) }
    var lastModelDownload by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(availability) {
        if (availability != AiAvailability.Checking) {
            val unsupported = availability as? AiAvailability.Unsupported
            lastUnsupported = unsupported?.reason
            lastDetail = unsupported?.detail
            lastModelDownload = unsupported?.modelDownload == true
        }
    }
    // While "Check Again" runs the screen stays, showing that it's checking.
    val unsupported = availability as? AiAvailability.Unsupported
    val unsupportedReason = unsupported?.reason ?: lastUnsupported.takeIf { availability == AiAvailability.Checking }
    unsupportedReason?.let { reason ->
        UnsupportedDeviceScreen(
            reason = reason,
            detail = unsupported?.detail ?: lastDetail,
            checking = availability == AiAvailability.Checking,
            onCheckAgain = viewModel::checkAiAgain,
            modelDownload = unsupported?.modelDownload ?: lastModelDownload,
            onDownloadModel = startModelDownload,
            standIn = DevelopmentStandIn.texts,
            onUseStandIn = { viewModel.useDevelopmentStandIn(true) },
        )
        return
    }

    if (!hasSeenWelcome) {
        OnboardingScreen(healthConnect, connectHealthConnect, onFinish = viewModel::finishOnboarding)
        return
    }

    val importState by viewModel.importState.collectAsStateWithLifecycle()
    var pasting by rememberSaveable { mutableStateOf(false) }

    when (val state = importState) {
        is ImportState.Reviewing -> {
            BackHandler { viewModel.cancelImport() }
            ReviewScreen(
                draft = state.draft,
                onUpdateDraft = viewModel::updateDraft,
                onUpdateValue = viewModel::updateValue,
                onAddValue = viewModel::addValue,
                onRemoveValue = viewModel::removeValue,
                onCancel = viewModel::cancelImport,
                onSave = viewModel::saveDraft,
            )
            return
        }
        else -> Unit
    }
    if (pasting) {
        BackHandler { pasting = false }
        PasteTextScreen(onCancel = { pasting = false }, onSubmit = { pasting = false; viewModel.importText(it) })
        return
    }

    Navigator(viewModel, healthConnect, connectHealthConnect, onPaste = { pasting = true })

    when (val state = importState) {
        ImportState.ReadingDocument -> ProcessingDialog(ProcessingStage.Reading, viewModel::cancelImport)
        is ImportState.PreparingModel -> ProcessingDialog(ProcessingStage.PreparingModel(state.preparing, state.engine), viewModel::cancelImport)
        is ImportState.Parsing -> ProcessingDialog(ProcessingStage.Parsing(state.progress), viewModel::cancelImport)
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text(stringResource(R.string.import_failed)) },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.ok)) } },
        )
        else -> Unit
    }
}

@Composable
private fun Navigator(
    viewModel: AppViewModel,
    healthConnect: HealthConnectState,
    connectHealthConnect: () -> Unit,
    onPaste: () -> Unit,
) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var stack by rememberSaveable { mutableStateOf(listOf("home")) }
    val current = Destination.decode(stack.last())
    val push = { d: Destination -> stack = stack + d.encode() }
    val pop = { if (stack.size > 1) stack = stack.dropLast(1) }
    // Reports in selection mode (to delete several) — the import button hides meanwhile.
    var historySelecting by remember { mutableStateOf(false) }
    LaunchedEffect(current) { if (current !is Destination.Reports) historySelecting = false }

    // A report that was deleted (or edited away) can't stay on screen; nor can a value without readings
    // (e.g. a launcher shortcut to a value whose reports were deleted).
    LaunchedEffect(reports, current, isLoading) {
        if (isLoading) return@LaunchedEffect
        if (current is Destination.Report && reports.none { it.id == current.id }) pop()
        if (current is Destination.Trend && reports.none { r -> r.entries.any { it.code == current.code && it.numericValue != null } }) {
            stack = stack.dropLast(1).ifEmpty { listOf(Destination.Home.encode()) }
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    val importActions = rememberImportActions(
        onDocuments = viewModel::importDocuments,
        onPaste = onPaste,
        onManual = viewModel::startManualEntry,
        onPrewarm = viewModel::prewarm,
        onError = { },
    )

    // Launcher shortcuts land here once the main screen shows.
    val launchRequest by viewModel.launchRequest.collectAsStateWithLifecycle()
    LaunchedEffect(launchRequest) {
        when (val request = launchRequest ?: return@LaunchedEffect) {
            LaunchRequest.Scan -> importActions.scan()
            is LaunchRequest.OpenMetric -> stack = listOf(Destination.Home.encode(), Destination.Trend(request.code).encode())
        }
        viewModel.consumeLaunchRequest()
    }

    // Wide windows get a navigation rail with per-section stacks (like the iPad sidebar);
    // switching layouts converts the stack so the user keeps their place.
    val window = windowSize()
    val rail = usesRail(window.width, window.height)
    LaunchedEffect(rail) { stack = stackForLayout(stack, rail) }
    // Back from Reports or Settings in the rail returns to the dashboard section.
    BackHandler(enabled = rail && stack.size == 1 && stack[0] != Destination.Home.encode()) { stack = listOf(Destination.Home.encode()) }

    // Predictive back: the current screen shrinks toward the edge with the
    // gesture, revealing the previous one underneath (Android 14+ style).
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backFromLeft by remember { mutableStateOf(true) }
    PredictiveBackHandler(enabled = stack.size > 1) { events: Flow<BackEventCompat> ->
        try {
            events.collect { backProgress = it.progress; backFromLeft = it.swipeEdge == BackEventCompat.EDGE_LEFT }
            backProgress = 0f
            pop()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    val screens = @Composable { modifier: Modifier ->
        // Lists centre at a readable width on wide windows (see readableListPadding).
        BoxWithConstraints(modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalScreenWidth provides maxWidth) {
                if (backProgress > 0f && stack.size > 1) {
                    Screen(
                        Destination.decode(stack[stack.size - 2]), viewModel, reports, isLoading, healthConnect, connectHealthConnect, importActions, push, pop,
                        railRoot = rail && stack.size == 2,
                    ) {}
                }
                AnimatedContent(
                    targetState = stack,
                    transitionSpec = {
                        val forward = targetState.size >= initialState.size
                        val spec = tween<IntOffset>(380)
                        if (forward) {
                            (slideInHorizontally(spec) { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally(spec) { -it / 8 } + fadeOut())
                        } else {
                            (slideInHorizontally(spec) { -it / 8 } + fadeIn()) togetherWith (slideOutHorizontally(spec) { it / 4 } + fadeOut())
                        }
                    },
                    contentKey = { it.last() },
                    label = "nav",
                    modifier = Modifier.graphicsLayer {
                        val p = backProgress
                        if (p > 0f) {
                            val scale = 1f - 0.1f * p
                            scaleX = scale
                            scaleY = scale
                            translationX = (if (backFromLeft) 1 else -1) * 24.dp.toPx() * p
                            shape = RoundedCornerShape(32.dp * p)
                            clip = true
                        }
                    },
                ) { target ->
                    // Opaque so the screen underneath only shows through the predictive-back inset.
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                        Screen(
                            Destination.decode(target.last()), viewModel, reports, isLoading, healthConnect, connectHealthConnect, importActions, push, pop,
                            railRoot = rail && target.size == 1,
                        ) { historySelecting = it }
                    }
                }
                // The import button steps aside while reports are being selected for deletion.
                val showFab = (current is Destination.Home || (current is Destination.Reports && !historySelecting)) && reports.isNotEmpty()
                if (showFab && backProgress == 0f) ImportFabMenu(importActions, Modifier.navigationBarsPadding().padding(end = 4.dp, bottom = 4.dp))
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
            }
        }
    }
    if (rail) {
        Row(Modifier.fillMaxSize()) {
            SectionRail(Section.of(stack), onSelect = { stack = listOf(it.root) })
            screens(Modifier.weight(1f))
        }
    } else {
        screens(Modifier)
    }
}

@Composable
private fun Screen(
    destination: Destination,
    viewModel: AppViewModel,
    reports: List<LabReport>,
    isLoading: Boolean,
    healthConnect: HealthConnectState,
    connectHealthConnect: () -> Unit,
    importActions: ImportActions,
    push: (Destination) -> Unit,
    pop: () -> Unit,
    /** At the root of a navigation-rail section: no back arrow, and the rail replaces the dashboard's buttons. */
    railRoot: Boolean = false,
    onSelectionModeChange: (Boolean) -> Unit,
) {
    val preferences by viewModel.displayPreferences.collectAsStateWithLifecycle()
    when (destination) {
        Destination.Home -> DashboardScreen(
            reports, preferences,
            notices = dashboardNotices(viewModel, connectHealthConnect),
            onOpenMetric = { push(Destination.Trend(it)) },
            importActions = importActions,
            isLoading = isLoading,
            onOpenReports = { push(Destination.Reports) }.takeUnless { railRoot },
            onOpenSettings = { push(Destination.Settings) }.takeUnless { railRoot },
            patientName = viewModel.patientName.collectAsStateWithLifecycle().value,
        )
        Destination.Reports -> HistoryScreen(
            reports, onOpenReport = { push(Destination.Report(it.id)) }, onBack = pop.takeUnless { railRoot },
            onExportPdf = { push(Destination.PdfExport) },
            onDeleteReports = viewModel::deleteReports,
            onSelectionModeChange = onSelectionModeChange,
        )
        Destination.PdfExport -> PdfExportScreen(reports, preferences, onGenerate = viewModel::exportPdf, onBack = pop)
        Destination.Settings -> {
            val patientName by viewModel.patientName.collectAsStateWithLifecycle()
            val patientDetails by viewModel.patientDetails.collectAsStateWithLifecycle()
            val aiAvailability by viewModel.aiAvailability.collectAsStateWithLifecycle()
            val usingStandIn by viewModel.usingStandIn.collectAsStateWithLifecycle()
            val modelInstalled by viewModel.modelInstalled.collectAsStateWithLifecycle()
            SettingsScreen(
                patient = PatientSettings(patientName, patientDetails, viewModel::setPatientName, viewModel::setPatientDetails),
                healthConnect = healthConnect,
                onConnectHealthConnect = connectHealthConnect,
                onUseHealthConnect = viewModel::setUseHealthConnect,
                ai = AiSettings(
                    aiAvailability, usingStandIn, onTryAgain = viewModel::checkAiAgain, onUseGemini = { viewModel.useDevelopmentStandIn(false) },
                    engine = viewModel.aiEngine, modelInstalled = modelInstalled,
                    onUseMobileData = { viewModel.downloadModel(allowMobileData = true) }, onRemoveModel = viewModel::removeModel,
                ),
                transfer = TransferSettings(reports.isNotEmpty(), viewModel::exportAllReports, viewModel::importReports),
                softwareIdentity = viewModel.softwareIdentity,
                onBack = pop.takeUnless { railRoot },
                onOpenSortEditor = { push(Destination.SortEditor) },
                onOpenCatalog = { push(Destination.Catalog) },
                search = SearchSettings(viewModel.showLatestValueInSearch.collectAsStateWithLifecycle().value, viewModel::setShowLatestValueInSearch),
                about = viewModel.appInfo.let { AboutSettings(it.repositoryUrl, it.issueReportUrl, it.fundingLinks, it.licenseText) },
            )
        }
        Destination.SortEditor -> SortEditorScreen(
            remember(reports) { Dashboard.codeNames(reports) }, preferences, viewModel::updateDisplayPreferences, onBack = pop,
            onOpenDetails = { push(Destination.Term(it)) },
        )
        Destination.Catalog -> LoincCatalogScreen(onOpenTerm = { push(Destination.Term(it)) }, onBack = pop)
        is Destination.Term -> LoincTermScreen(destination.code, preferences, viewModel::updateDisplayPreferences, onBack = pop)
        is Destination.Trend -> TrendScreen(
            destination.code, reports, preferences, viewModel::updateDisplayPreferences, onBack = pop,
            onOpenTerm = { push(Destination.Term(it)) },
        )
        is Destination.Report -> {
            val report = reports.firstOrNull { it.id == destination.id } ?: return
            ReportDetailScreen(
                report = report,
                onBack = pop,
                onEdit = { viewModel.editReport(report) },
                onDelete = { viewModel.deleteReport(report) },
                exportCda = { viewModel.exportCda(report) },
                onOpenTerm = { push(Destination.Term(it)) },
            )
        }
    }
}

/**
 * The dashboard's notices: the model download while it isn't ready, and the
 * offer to move reports into Health Connect once it's available. Null when
 * there's nothing to say.
 */
@Composable
private fun dashboardNotices(viewModel: AppViewModel, connectHealthConnect: () -> Unit): (@Composable () -> Unit)? {
    val availability by viewModel.aiAvailability.collectAsStateWithLifecycle()
    val offerHealthConnect by viewModel.offerHealthConnect.collectAsStateWithLifecycle()
    val modelNotice = availability is AiAvailability.Preparing || availability is AiAvailability.PreparationFailed
    if (!modelNotice && !offerHealthConnect) return null
    return {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (modelNotice) {
                ModelStatusCard(
                    availability, viewModel.aiEngine, onTryAgain = viewModel::checkAiAgain,
                    onUseMobileData = { viewModel.downloadModel(allowMobileData = true) },
                )
            }
            if (offerHealthConnect) HealthConnectOfferCard(onConnect = connectHealthConnect, onDismiss = viewModel::dismissHealthConnectOffer)
        }
    }
}
