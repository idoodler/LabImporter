package dev.idoodler.labimporter.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ui.dashboard.DashboardScreen
import dev.idoodler.labimporter.ui.dashboard.TrendScreen
import dev.idoodler.labimporter.ui.history.HistoryScreen
import dev.idoodler.labimporter.ui.history.ReportDetailScreen
import dev.idoodler.labimporter.ui.importflow.ImportSheet
import dev.idoodler.labimporter.ui.importflow.PasteTextScreen
import dev.idoodler.labimporter.ui.importflow.ProcessingDialog
import dev.idoodler.labimporter.ui.onboarding.OnboardingScreen
import dev.idoodler.labimporter.ui.review.ReviewScreen
import dev.idoodler.labimporter.ui.settings.SettingsScreen

enum class Tab(val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    DASHBOARD(R.string.tab_dashboard, Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
    HISTORY(R.string.tab_history, Icons.Outlined.History, Icons.Filled.History),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/**
 * App shell. A `NavigationSuiteScaffold` adapts between bottom bar (phones),
 * rail (tablets/foldables) and drawer (desktop windows) — the Android
 * equivalent of the iOS tab bar ⇄ iPad sidebar.
 */
@Composable
fun LabImporterRoot(viewModel: AppViewModel) {
    val hasSeenWelcome by viewModel.hasSeenWelcome.collectAsStateWithLifecycle()
    val healthConnect by viewModel.healthConnect.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.onHealthConnectPermissionsResult(it)
    }
    val connectHealthConnect = { permissionLauncher.launch(viewModel.healthConnectPermissions) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

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

    MainScaffold(viewModel, healthConnect, connectHealthConnect, onPaste = { pasting = true })

    when (val state = importState) {
        ImportState.ReadingDocument -> ProcessingDialog(null, viewModel::cancelImport)
        is ImportState.Parsing -> ProcessingDialog(state.progress, viewModel::cancelImport)
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
private fun MainScaffold(
    viewModel: AppViewModel,
    healthConnect: HealthConnectState,
    connectHealthConnect: () -> Unit,
    onPaste: () -> Unit,
) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val preferences by viewModel.displayPreferences.collectAsStateWithLifecycle()
    val patientName by viewModel.patientName.collectAsStateWithLifecycle()
    val aiStatus by viewModel.aiStatus.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    var metric by rememberSaveable { mutableStateOf<String?>(null) }
    var reportId by rememberSaveable { mutableStateOf<String?>(null) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { item ->
                item(
                    selected = tab == item,
                    onClick = { tab = item; metric = null; reportId = null },
                    icon = { Icon(if (tab == item) item.selectedIcon else item.icon, null) },
                    label = { Text(stringResource(item.label)) },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            val selectedReport = reports.firstOrNull { it.id == reportId }
            AnimatedContent(
                targetState = Triple(tab, metric, selectedReport?.id),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "content",
            ) { (currentTab, currentMetric, currentReportId) ->
                val currentReport = reports.firstOrNull { it.id == currentReportId }
                when {
                    currentTab == Tab.DASHBOARD && currentMetric != null -> {
                        BackHandler { metric = null }
                        TrendScreen(currentMetric, reports, preferences, viewModel::updateDisplayPreferences, onBack = { metric = null })
                    }
                    currentTab == Tab.HISTORY && currentReport != null -> {
                        BackHandler { reportId = null }
                        ReportDetailScreen(
                            report = currentReport,
                            onBack = { reportId = null },
                            onEdit = { viewModel.editReport(currentReport) },
                            onDelete = { reportId = null; viewModel.deleteReport(currentReport) },
                            exportCda = { viewModel.exportCda(currentReport) },
                        )
                    }
                    currentTab == Tab.DASHBOARD -> DashboardScreen(reports, preferences, { metric = it }, { showImport = true })
                    currentTab == Tab.HISTORY -> HistoryScreen(reports, { reportId = it.id }, onImport = { showImport = true })
                    else -> SettingsScreen(
                        patientName = patientName,
                        onPatientName = viewModel::setPatientName,
                        healthConnect = healthConnect,
                        onConnectHealthConnect = connectHealthConnect,
                        onUseHealthConnect = viewModel::setUseHealthConnect,
                        aiStatus = aiStatus,
                        onDownloadModel = viewModel::downloadModel,
                        preferences = preferences,
                        onUpdatePreferences = viewModel::updateDisplayPreferences,
                        softwareIdentity = viewModel.softwareIdentity,
                    )
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
        }
    }

    if (showImport) {
        ImportSheet(
            onDismiss = { showImport = false },
            onDocuments = { showImport = false; viewModel.importDocuments(it) },
            onPaste = { showImport = false; onPaste() },
            onManual = { showImport = false; viewModel.startManualEntry() },
            onPrewarm = viewModel::prewarm,
            onError = { showImport = false },
        )
    }
}
