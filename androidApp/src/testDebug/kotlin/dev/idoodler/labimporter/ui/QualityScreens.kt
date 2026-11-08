package dev.idoodler.labimporter.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.AiProblem
import dev.idoodler.labimporter.ai.UnsupportedReason
import dev.idoodler.labimporter.core.loinc.LoincTerm
import dev.idoodler.labimporter.core.model.CodeName
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.data.HealthConnectAvailability
import dev.idoodler.labimporter.data.ReportsLoadProblem
import dev.idoodler.labimporter.ui.dashboard.DashboardScreen
import dev.idoodler.labimporter.ui.dashboard.LoadProblemActions
import dev.idoodler.labimporter.ui.dashboard.NewerVersionReportsCard
import dev.idoodler.labimporter.ui.dashboard.ReportsLoadFailedCard
import dev.idoodler.labimporter.ui.dashboard.TrendScreen
import dev.idoodler.labimporter.ui.dashboard.UnreadableReportsCard
import dev.idoodler.labimporter.ui.export.PdfExportScreen
import dev.idoodler.labimporter.ui.history.HistoryScreen
import dev.idoodler.labimporter.ui.history.ReportDetailScreen
import dev.idoodler.labimporter.ui.importflow.ImportActions
import dev.idoodler.labimporter.ui.importflow.PasteTextScreen
import dev.idoodler.labimporter.ui.loinc.LoincCatalogScreen
import dev.idoodler.labimporter.ui.loinc.LoincTermScreen
import dev.idoodler.labimporter.ui.onboarding.OnboardingScreen
import dev.idoodler.labimporter.ui.onboarding.UnsupportedDeviceScreen
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.review.ReviewScreen
import dev.idoodler.labimporter.ui.settings.AiSettings
import dev.idoodler.labimporter.ui.settings.PatientSettings
import dev.idoodler.labimporter.ui.settings.SettingsScreen
import dev.idoodler.labimporter.ui.settings.SortEditorScreen
import kotlinx.datetime.LocalDate

/**
 * Every screen in the states it draws differently, for the accessibility audit
 * and the screenshot matrix. New screens belong here: what isn't listed isn't checked.
 */
internal data class QualityScreen(
    val name: String,
    /** A text the screen shows once what it loads in the background is there; until then it's checked half-drawn. */
    val shows: String? = null,
    val content: @Composable () -> Unit,
)

/** Waits for what [screen] loads on an IO dispatcher, which `waitForIdle` doesn't wait for. */
internal fun ComposeTestRule.awaitLoaded(screen: QualityScreen) {
    waitForIdle()
    val text = screen.shows ?: return
    waitFor { onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
}

private val importActions = ImportActions({}, {}, {}, {}, {})

private fun review(values: List<LabValue>, engine: AiEngine? = AiEngine.GEMINI_NANO): @Composable () -> Unit = {
    ReviewScreen(ReviewDraft(values, LocalDate(2026, 5, 6), "Max Mustermann", "Labor", engine), {}, { _, _ -> }, { "" }, {}, {}, {})
}

private fun settings(
    ai: AiSettings = AiSettings(AiAvailability.Ready),
    healthConnect: HealthConnectState = HealthConnectState(),
): @Composable () -> Unit = {
    SettingsScreen(PatientSettings(), healthConnect, {}, {}, ai, "LabImporter test")
}

private val catalogTerms = listOf(
    LoincTerm("2160-0", "Creatinine [Mass/volume] in Serum or Plasma", "Creatinine", null, "mg/dL", 1),
    LoincTerm("4548-4", "Hemoglobin A1c/Hemoglobin.total in Blood", "HbA1c", null, "%", 2),
)

/** A misread value, a duplicate, one that can't be saved, one without a code and one with no result. */
private val reviewWithErrors = listOf(
    LabValue("a", "2345-7", "Glucose", "922", 922.0, "mg/dL", needsSourceCheck = true),
    LabValue("b", "2160-0", "Creatinine", "0.8", 0.8, "mg/dL"),
    LabValue("c", "2160-0", "Creatinine", "0.9", 0.9, "mg/dL"),
    LabValue("d", "KREA", "Krea", "1.0", 1.0, "mg/dL"),
    LabValue("e", "4548-4", "HbA1c", "6.5 %", null, ""),
)

internal val qualityScreens: List<QualityScreen> = listOf(
    QualityScreen("dashboard") { DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, importActions) },
    QualityScreen("dashboard-qualitative") { DashboardScreen(PreviewSamples.reportsWithQualitative, LabDisplayPreferences(), {}, importActions) },
    QualityScreen("dashboard-empty") { DashboardScreen(emptyList(), LabDisplayPreferences(), {}, importActions) },
    QualityScreen("dashboard-load-failed") {
        DashboardScreen(
            emptyList(), LabDisplayPreferences(), {}, importActions,
            loadProblem = ReportsLoadProblem.READ_FAILED, loadProblemActions = LoadProblemActions(onTryAgain = {}),
            notices = { UnreadableReportsCard(2) },
        )
    },
    QualityScreen("dashboard-load-failed-stale") {
        DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, importActions, loadProblem = ReportsLoadProblem.READ_FAILED)
    },
    QualityScreen("trend") { TrendScreen("4548-4", PreviewSamples.reports, LabDisplayPreferences(), {}, {}) },
    QualityScreen("trend-qualitative") { TrendScreen("5196-1", PreviewSamples.reportsWithQualitative, LabDisplayPreferences(), {}, {}) },
    QualityScreen("reports") { HistoryScreen(PreviewSamples.reports, {}) },
    QualityScreen("reports-empty") { HistoryScreen(emptyList(), {}) },
    QualityScreen("reports-load-failed") {
        HistoryScreen(emptyList(), {}, loadProblem = ReportsLoadProblem.READ_FAILED, loadProblemActions = LoadProblemActions(onTryAgain = {}))
    },
    QualityScreen("reports-newer") { HistoryScreen(emptyList(), {}, notices = { NewerVersionReportsCard(2) }) },
    QualityScreen("report-detail") { ReportDetailScreen(PreviewSamples.reports[0], {}, {}, {}, { Uri.EMPTY }) },
    QualityScreen("review") { review(PreviewSamples.reviewValues)() },
    QualityScreen("review-errors") { review(reviewWithErrors)() },
    QualityScreen("review-stand-in") { review(PreviewSamples.reviewValues, AiEngine.DEVELOPMENT_STAND_IN)() },
    QualityScreen("settings") { settings()() },
    QualityScreen("settings-preparing") { settings(AiSettings(AiAvailability.Preparing(1, 4)))() },
    QualityScreen("settings-failed") { settings(AiSettings(AiAvailability.PreparationFailed(AiProblem.NOT_ENOUGH_STORAGE), onTryAgain = {}))() },
    QualityScreen("settings-no-health-connect") {
        settings(healthConnect = HealthConnectState(HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED, chosen = true))()
    },
    QualityScreen("sort-editor") {
        val codes = listOf(CodeName("4548-4", "HbA1c"), CodeName("2160-0", "Creatinine"), CodeName("2093-3", "Cholesterol"))
        SortEditorScreen(codes, LabDisplayPreferences(pinnedCodes = listOf("4548-4"), hiddenCodes = listOf("2093-3")), {}, {})
    },
    QualityScreen("pdf-export") { PdfExportScreen(PreviewSamples.reports, LabDisplayPreferences(), onGenerate = { null }, onBack = {}) },
    QualityScreen("catalog", shows = catalogTerms.last().name) { LoincCatalogScreen(onOpenTerm = {}, onBack = {}, search = { _, offset -> if (offset > 0) emptyList() else catalogTerms }) },
    QualityScreen("term") { LoincTermScreen("2160-0", LabDisplayPreferences(), {}, {}) },
    QualityScreen("paste") { PasteTextScreen({}, {}) },
    QualityScreen("onboarding-welcome") { OnboardingScreen(HealthConnectState(), {}, {}, initialStep = 0) },
    QualityScreen("onboarding-disclaimer") { OnboardingScreen(HealthConnectState(), {}, {}, initialStep = 1) },
    QualityScreen("onboarding-health") { OnboardingScreen(HealthConnectState(), {}, {}, initialStep = 2) },
    QualityScreen("unsupported") { UnsupportedDeviceScreen(UnsupportedReason.DEVICE, checking = false, onCheckAgain = {}) },
    QualityScreen("unsupported-offer") {
        UnsupportedDeviceScreen(
            UnsupportedReason.DEVICE, checking = false, onCheckAgain = {}, detail = "Prompt API: UNAVAILABLE",
            modelDownload = true, onDownloadModel = {},
        )
    },
    QualityScreen("load-failed-card") { ReportsLoadFailedCard(ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF, LoadProblemActions(onAllowAccess = {}, onUseAppStorage = {})) },
)
