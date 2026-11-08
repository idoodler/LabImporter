package dev.idoodler.labimporter.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.R
import org.robolectric.Shadows.shadowOf
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.AiProblem
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.core.loinc.LoincTerm
import dev.idoodler.labimporter.core.model.CodeName
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.ui.dashboard.DashboardScreen
import dev.idoodler.labimporter.ui.dashboard.LoadProblemActions
import dev.idoodler.labimporter.ui.dashboard.NewerVersionReportsCard
import dev.idoodler.labimporter.ui.dashboard.ReportsLoadFailedCard
import dev.idoodler.labimporter.ui.dashboard.TrendScreen
import dev.idoodler.labimporter.ui.dashboard.UnreadableReportsCard
import dev.idoodler.labimporter.ui.importflow.ImportActions
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.data.HealthConnectAvailability
import dev.idoodler.labimporter.data.PatientDetails
import dev.idoodler.labimporter.data.ReportsLoadProblem
import dev.idoodler.labimporter.data.PatientSex
import dev.idoodler.labimporter.export.PdfOptions
import dev.idoodler.labimporter.ui.export.PdfExportScreen
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.history.HistoryScreen
import dev.idoodler.labimporter.ui.loinc.LoincCatalogScreen
import dev.idoodler.labimporter.ui.loinc.LoincTermScreen
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.review.ReviewScreen
import dev.idoodler.labimporter.ui.settings.AiSettings
import dev.idoodler.labimporter.ui.settings.PatientSettings
import dev.idoodler.labimporter.ui.settings.SettingsScreen
import dev.idoodler.labimporter.ui.settings.TransferSettings
import dev.idoodler.labimporter.ui.settings.SortEditorScreen
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlinx.datetime.LocalDate
import java.time.format.FormatStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Screen-level rules that must survive refactoring. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "w411dp-h914dp")
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TestApplication get() = ApplicationProvider.getApplicationContext()
    private fun string(id: Int) = app.getString(id)

    private fun value(id: String, code: String, number: Double?) =
        LabValue(id = id, code = code, name = code, displayValue = number?.toString() ?: "-", numericValue = number, unit = "mg/dl")

    private fun review(values: List<LabValue>, engine: AiEngine? = AiEngine.GEMINI_NANO, onSave: () -> Unit = {}) {
        val draft = ReviewDraft(values, LocalDate(2026, 5, 6), "Max Mustermann", "Labor", engine)
        compose.setContent { LabImporterTheme { ReviewScreen(draft, {}, { _, _ -> }, { "" }, {}, {}, onSave) } }
    }

    @Test
    fun reviewBlocksSavingTwoValuesWithTheSameLoinc() {
        review(listOf(value("a", "2160-0", 0.8), value("b", "2160-0", 0.9)))
        compose.onAllNodesWithText(string(R.string.save)).fetchSemanticsNodes().indices.forEach {
            compose.onAllNodesWithText(string(R.string.save))[it].assertIsNotEnabled()
        }
        compose.onNodeWithText(string(R.string.duplicates_short)).assertIsDisplayed()
    }

    @Test
    fun reviewSavesUniqueExportableValues() {
        var saved = 0
        review(listOf(value("a", "2160-0", 0.8), value("b", "KREA", 1.0)), onSave = { saved++ })
        compose.onAllNodesWithText(string(R.string.save)).onFirst().assertIsEnabled().performClick()
        assertEquals(1, saved)
    }

    @Test
    fun reviewNeedsAtLeastOneExportableValue() {
        review(listOf(value("a", "KREA", 1.0), value("b", "2160-0", null)))
        compose.onAllNodesWithText(string(R.string.save)).onFirst().assertIsNotEnabled()
    }

    @Test
    fun standInResultsAreLabelledAsSamples() {
        review(listOf(value("a", "2160-0", 0.8)), engine = AiEngine.DEVELOPMENT_STAND_IN)
        compose.onNodeWithText(checkNotNull(DevelopmentStandIn.texts).reviewBanner).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.ai_title)).assertDoesNotExist()
    }

    private fun settings(ai: AiSettings) {
        compose.setContent {
            LabImporterTheme {
                SettingsScreen(PatientSettings(), HealthConnectState(), {}, {}, ai, "LabImporter test")
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(string(R.string.ai_title)))
    }

    @Test
    fun settingsShowTheModelDownload() {
        settings(AiSettings(AiAvailability.Preparing(1, 4)))
        compose.onNodeWithText(string(R.string.processing_preparing)).assertIsDisplayed()
    }

    @Test
    fun settingsExplainAFailedDownloadAndRetry() {
        var retries = 0
        settings(AiSettings(AiAvailability.PreparationFailed(AiProblem.NOT_ENOUGH_STORAGE), onTryAgain = { retries++ }))
        compose.onNodeWithText(string(R.string.ai_problem_storage)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.try_again)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun settingsLetDevelopersLeaveTheStandIn() {
        var switched = 0
        settings(AiSettings(AiAvailability.Ready, usingStandIn = true, onUseGemini = { switched++ }))
        val texts = checkNotNull(DevelopmentStandIn.texts)
        compose.onNodeWithText(texts.active).assertIsDisplayed()
        compose.onNodeWithText(texts.useGemini).performClick()
        assertEquals(1, switched)
    }

    @Test
    fun settingsEditThePatientDetailsForThePdf() {
        var details = PatientDetails()
        compose.setContent {
            LabImporterTheme {
                SettingsScreen(
                    PatientSettings(details = PatientDetails(LocalDate(1980, 4, 2)), onDetails = { details = it }),
                    HealthConnectState(), {}, {}, AiSettings(AiAvailability.Ready), "LabImporter test",
                )
            }
        }
        compose.onNodeWithText(string(R.string.date_of_birth)).assertIsDisplayed()
        // The sex starts unset and is chosen from a menu.
        compose.onNodeWithText(string(R.string.not_set)).performClick()
        compose.onNodeWithText(string(R.string.sex_female)).performClick()
        assertEquals(PatientDetails(LocalDate(1980, 4, 2), PatientSex.FEMALE), details)
        // A set date of birth can be cleared.
        compose.onNodeWithContentDescription(string(R.string.clear)).performClick()
        assertEquals(PatientDetails(null, null), details.copy(sex = null))
    }

    @Test
    fun pdfExportNeedsAValueAndRendersOnGenerate() {
        val generated = mutableListOf<PdfOptions>()
        compose.setContent {
            LabImporterTheme {
                PdfExportScreen(PreviewSamples.reports, LabDisplayPreferences(), onGenerate = { generated += it; null }, onBack = {})
            }
        }
        val generate = compose.onNodeWithText(string(R.string.pdf_generate))
        // Everything with a numeric reading starts selected (4 values in the samples).
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(app.getString(R.string.pdf_selected_count, 4, 4)))
        compose.onNodeWithText(string(R.string.deselect_all)).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(string(R.string.pdf_select_one)))
        generate.assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.select_all)).performClick()
        generate.assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(setOf("4548-4", "2160-0", "2093-3", "3016-3"), generated.single().selectedCodes)
        // A failed render says so instead of sharing nothing.
        compose.onNodeWithText(string(R.string.pdf_failed)).assertIsDisplayed()
    }

    @Test
    fun sortEditorPinsHidesRestoresAndMovesWithAccessibilityActions() {
        val codes = listOf(CodeName("4548-4", "HbA1c"), CodeName("2160-0", "Creatinine"), CodeName("2093-3", "Cholesterol"))
        var prefs by mutableStateOf(LabDisplayPreferences())
        compose.setContent { LabImporterTheme { SortEditorScreen(codes, prefs, { prefs = it(prefs) }, {}) } }

        compose.onAllNodesWithContentDescription(string(R.string.hide))[1].performClick()
        assertEquals(listOf("2160-0"), prefs.hiddenCodes)
        compose.onNodeWithText(string(R.string.restore)).performClick()
        assertEquals(emptyList<String>(), prefs.hiddenCodes)
        assertEquals(listOf("4548-4", "2093-3", "2160-0"), prefs.orderedCodes)

        compose.onAllNodesWithContentDescription(string(R.string.pin))[2].performClick()
        assertEquals(listOf("2160-0"), prefs.pinnedCodes)
        compose.onNodeWithText(string(R.string.pinned)).assertIsDisplayed()

        // Rows move without dragging, for TalkBack and switch access.
        val moveDown = string(R.string.move_down)
        val first = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
            .first { node -> node.config[SemanticsActions.CustomActions].any { it.label == moveDown } && node.config[SemanticsActions.CustomActions].size == 1 }
        compose.runOnIdle { first.config[SemanticsActions.CustomActions].single().action() }
        assertEquals(listOf("2160-0", "2093-3", "4548-4"), prefs.orderedCodes)
    }

    @Test
    fun catalogSearchesAndOpensATerm() {
        val opened = mutableListOf<String>()
        val terms = listOf(
            LoincTerm("2160-0", "Creatinine [Mass/volume] in Serum or Plasma", "Creatinine", null, "mg/dL", 1),
            LoincTerm("4548-4", "Hemoglobin A1c/Hemoglobin.total in Blood", "HbA1c", null, "%", 2),
        )
        // The catalog query itself is the shared, tested LoincDirectory; here it's a fixed list.
        val search = { query: String, offset: Int ->
            if (offset > 0) emptyList() else terms.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        }
        compose.setContent { LabImporterTheme { LoincCatalogScreen(onOpenTerm = { opened += it }, onBack = {}, search = search) } }
        compose.waitFor { compose.onAllNodesWithText("4548-4").fetchSemanticsNodes().isNotEmpty() }
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("creatinine")
        compose.waitFor { compose.onAllNodesWithText("4548-4").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("2160-0").performClick()
        assertEquals(listOf("2160-0"), opened)

        field.performTextClearance()
        field.performTextInput("zzqqxxnotatest")
        compose.waitFor { compose.onAllNodesWithText(string(R.string.no_results)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun termScreenShowsTheCatalogAttributesAndOffersEdits() {
        compose.setContent {
            LabImporterTheme { LoincTermScreen("2160-0", LabDisplayPreferences(nicknames = mapOf("2160-0" to "Krea")), {}, {}) }
        }
        compose.onNodeWithText("Krea").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(string(R.string.loinc_component)))
        compose.onNodeWithText(string(R.string.loinc_component)).assertIsDisplayed()
        // Both edits are offered (the dialogs' own logic is the shared, tested preferences code;
        // Robolectric never idles with a text field inside a dialog window).
        compose.onNodeWithContentDescription(string(R.string.more)).performClick()
        compose.onNodeWithText(string(R.string.rename)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.set_reference_range)).assertIsDisplayed()
    }

    @Test
    fun reportsCanBeSelectedAndDeletedTogether() {
        val deleted = mutableListOf<Set<String>>()
        compose.setContent { LabImporterTheme { HistoryScreen(PreviewSamples.reports, {}, onDeleteReports = { deleted += it }) } }
        compose.onNodeWithContentDescription(string(R.string.edit)).performClick()
        // Nothing selected yet: Delete is off.
        compose.onNodeWithText(string(R.string.delete)).assertIsNotEnabled()
        compose.onNodeWithText(formatDate(PreviewSamples.reports[1].date, FormatStyle.LONG)).performClick()
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.selected_count, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.delete)).performClick()
        compose.onNodeWithText(string(R.string.delete_report_title)).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.delete)).onLast().performClick()
        assertEquals(listOf(setOf("r2")), deleted)

        // Delete All asks about every report.
        compose.onNodeWithContentDescription(string(R.string.edit)).performClick()
        compose.onNodeWithText(string(R.string.delete_all)).performClick()
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.delete_reports_message, 3, 3)).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.delete)).onLast().performClick()
        assertEquals(setOf("r1", "r2", "r3"), deleted.last())
    }

    @Test
    fun aFailedLoadIsShownInsteadOfTheEmptyDashboard() {
        var retries = 0
        compose.setContent {
            LabImporterTheme {
                DashboardScreen(
                    emptyList(), LabDisplayPreferences(), {}, ImportActions({}, {}, {}, {}, {}),
                    loadProblem = ReportsLoadProblem.READ_FAILED, loadProblemActions = LoadProblemActions(onTryAgain = { retries++ }), notices = { UnreadableReportsCard(2) },
                )
            }
        }
        compose.onNodeWithText(string(R.string.reports_load_failed_title)).assertIsDisplayed()
        compose.onNodeWithText(ApplicationProvider.getApplicationContext<TestApplication>().getString(R.string.reports_unreadable_message, 2)).assertExists()
        compose.onNodeWithText(string(R.string.import_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.try_again)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun aFailedLoadStaysOnScreenAboveReportsAlreadyShown() {
        compose.setContent {
            LabImporterTheme {
                DashboardScreen(PreviewSamples.reports, LabDisplayPreferences(), {}, ImportActions({}, {}, {}, {}, {}), loadProblem = ReportsLoadProblem.READ_FAILED)
            }
        }
        compose.onNodeWithText(string(R.string.reports_load_failed_title)).assertExists()
    }

    @Test
    fun aFailedLoadIsShownInsteadOfTheEmptyReportList() {
        var retries = 0
        compose.setContent { LabImporterTheme { HistoryScreen(emptyList(), {}, loadProblem = ReportsLoadProblem.READ_FAILED, loadProblemActions = LoadProblemActions(onTryAgain = { retries++ })) } }
        compose.onNodeWithText(string(R.string.reports_load_failed_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.history_empty_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.try_again)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun aFailedLoadStaysOnScreenAboveReportsInTheList() {
        compose.setContent { LabImporterTheme { HistoryScreen(PreviewSamples.reports, {}, loadProblem = ReportsLoadProblem.READ_FAILED) } }
        compose.onNodeWithText(string(R.string.reports_load_failed_title)).assertExists()
    }

    @Test
    fun aWithdrawnPermissionOffersWhatHelpsInsteadOfTryAgain() {
        var allowed = 0
        var appStorage = 0
        compose.setContent {
            LabImporterTheme {
                ReportsLoadFailedCard(
                    ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF,
                    LoadProblemActions(onAllowAccess = { allowed++ }, onUseAppStorage = { appStorage++ }),
                )
            }
        }
        compose.onNodeWithText(string(R.string.hc_access_off_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.try_again)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.hc_allow_access)).performClick()
        assertEquals(1, allowed)
        compose.onNodeWithText(string(R.string.hc_use_app_storage)).performClick()
        assertEquals(1, appStorage)
    }

    @Test
    fun aRequiredUpdateOpensTheStore() {
        compose.setContent { LabImporterTheme { ReportsLoadFailedCard(ReportsLoadProblem.HEALTH_CONNECT_UPDATE_REQUIRED, LoadProblemActions()) } }
        compose.onNodeWithText(string(R.string.hc_needs_update_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.try_again)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.hc_update)).performClick()
        compose.waitForIdle()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals("market", opened.data?.scheme)
        assertEquals("com.google.android.apps.healthdata", opened.data?.getQueryParameter("id"))
    }

    @Test
    fun healthConnectGoneForGoodOffersAppStorageFirst() {
        var appStorage = 0
        compose.setContent {
            LabImporterTheme { ReportsLoadFailedCard(ReportsLoadProblem.HEALTH_CONNECT_UNAVAILABLE, LoadProblemActions(onUseAppStorage = { appStorage++ })) }
        }
        compose.onNodeWithText(string(R.string.hc_gone_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.hc_use_app_storage)).performClick()
        assertEquals(1, appStorage)
    }

    @Test
    fun settingsAreAWayBackToAppStorageWhenHealthConnectIsGone() {
        var used: Boolean? = null
        var state by mutableStateOf(HealthConnectState(HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED, chosen = true))
        compose.setContent {
            LabImporterTheme {
                SettingsScreen(PatientSettings(), state, {}, { used = it }, AiSettings(AiAvailability.Ready), "LabImporter test")
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(string(R.string.hc_use_app_storage)))
        compose.onNodeWithText(string(R.string.hc_gone_message)).assertExists()
        compose.onNodeWithText(string(R.string.hc_use_app_storage)).performClick()
        assertEquals(false, used)

        // Without the choice standing, there's nothing to get back from.
        state = HealthConnectState(HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED)
        compose.onNodeWithText(string(R.string.hc_use_app_storage)).assertDoesNotExist()
    }

    @Test
    fun anUpdateNoticeStaysAboveAnEmptyReportList() {
        compose.setContent { LabImporterTheme { HistoryScreen(emptyList(), {}, notices = { NewerVersionReportsCard(2) }) } }
        compose.onNodeWithText(string(R.string.reports_newer_title)).assertIsDisplayed()
    }

    @Test
    fun aNoticeSaysReportsNeedAnUpdate() {
        compose.setContent { LabImporterTheme { NewerVersionReportsCard(3) } }
        compose.onNodeWithText(string(R.string.reports_newer_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.reports_newer_message, 3)).assertIsDisplayed()
    }

    @Test
    fun anExportThatLeavesValuesOutAsksBeforeSharing() {
        val asked = mutableListOf<Boolean>()
        val uri = android.net.Uri.parse("content://example/file.json")
        val transfer = TransferSettings(
            hasReports = true,
            onExport = { allow ->
                asked += allow
                if (allow) ReportsExport.Ready(uri) else ReportsExport.LeavesOut(2)
            },
        )
        compose.setContent {
            LabImporterTheme { SettingsScreen(PatientSettings(), HealthConnectState(), {}, {}, AiSettings(AiAvailability.Ready), "LabImporter test", transfer = transfer) }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(string(R.string.transfer_export)))
        compose.onNodeWithText(string(R.string.transfer_export)).performClick()
        compose.onNodeWithText(app.getString(R.string.transfer_leaves_out_message, 2)).assertIsDisplayed()
        assertEquals(listOf(false), asked)

        compose.onNodeWithText(string(R.string.transfer_share_anyway)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(false, true), asked)
        assertEquals(android.content.Intent.ACTION_CHOOSER, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun anEmptyReportListWithoutAProblemSaysThereAreNone() {
        compose.setContent { LabImporterTheme { HistoryScreen(emptyList(), {}) } }
        compose.onNodeWithText(string(R.string.history_empty_title)).assertExists()
        compose.onNodeWithText(string(R.string.reports_load_failed_title)).assertDoesNotExist()
    }

    @Test
    fun anEmptyDashboardWithoutAProblemStillInvitesAnImport() {
        compose.setContent {
            LabImporterTheme { DashboardScreen(emptyList(), LabDisplayPreferences(), {}, ImportActions({}, {}, {}, {}, {})) }
        }
        compose.onNodeWithText(string(R.string.import_title)).assertExists()
        compose.onNodeWithText(string(R.string.reports_load_failed_title)).assertDoesNotExist()
    }

    @Test
    fun resultsThatArentNumbersAreListedOnTheDashboardAndTheTrendScreen() {
        var opened by mutableStateOf<String?>(null)
        compose.setContent {
            LabImporterTheme {
                val code = opened
                if (code == null) {
                    DashboardScreen(PreviewSamples.reportsWithQualitative, LabDisplayPreferences(), { opened = it }, ImportActions({}, {}, {}, {}, {}))
                } else {
                    TrendScreen(code, PreviewSamples.reportsWithQualitative, LabDisplayPreferences(), {}, {})
                }
            }
        }
        // The CRP card shows its latest result; the hepatitis B card its latest and the ones before.
        val grid = compose.onAllNodes(hasScrollAction()).onFirst()
        grid.performScrollToNode(hasText("<0,5", substring = true))
        compose.onAllNodesWithText("<0,5", substring = true).onFirst().assertIsDisplayed()
        grid.performScrollToNode(hasText("reaktiv"))
        compose.onAllNodesWithText("reaktiv").onFirst().performClick()
        assertEquals("5196-1", opened)

        // The trend screen lists every result, and there's no reference range to set for words.
        compose.onNodeWithText(string(R.string.readings)).assertExists()
        compose.onAllNodesWithText("nicht reaktiv").assertCountEquals(2)
        compose.onNodeWithContentDescription(string(R.string.more)).performClick()
        compose.onNodeWithText(string(R.string.reference_range)).assertDoesNotExist()
    }

    /** Vitamin D, with gaps of two weeks, two years and four months. */
    private val irregularVitaminD = listOf(
        LocalDate(2024, 1, 10) to 32.0,
        LocalDate(2024, 1, 24) to 35.0,
        LocalDate(2026, 1, 24) to 33.0,
        LocalDate(2026, 5, 28) to 34.0,
    ).mapIndexed { index, (date, number) ->
        LabReport("r$index", date, "", "", listOf(LabReport.Entry("e$index", "1989-3", "Vitamin D", number.toString(), number, "ng/mL")))
    }

    private fun trendChart(preferences: LabDisplayPreferences = LabDisplayPreferences()) {
        compose.setContent { LabImporterTheme { TrendScreen("1989-3", irregularVitaminD, preferences, {}, {}) } }
    }

    private fun readingLabel(date: LocalDate, value: String) =
        app.getString(R.string.chart_reading, formatDate(date, FormatStyle.LONG), value)

    @Test
    fun talkBackReadsEveryReadingOfTheChart() {
        trendChart(LabDisplayPreferences().withReferenceRange(ReferenceRange(low = 33.0), "1989-3"))
        val low = string(R.string.range_low)
        irregularVitaminD.forEach { report ->
            val number = report.entries.single().numericValue!!
            val status = if (number < 33.0) ", $low" else ""
            compose.onNodeWithContentDescription(readingLabel(report.date, "${number.toInt()} ng/mL$status")).assertExists()
        }
    }

    @Test
    fun theChartSpacesReadingsByDate() {
        trendChart()
        val lefts = irregularVitaminD.map { report ->
            val number = report.entries.single().numericValue!!.toInt()
            compose.onNodeWithContentDescription(readingLabel(report.date, "$number ng/mL")).fetchSemanticsNode().boundsInRoot.left
        }
        val twoWeeks = lefts[1] - lefts[0]
        val twoYears = lefts[2] - lefts[1]
        val fourMonths = lefts[3] - lefts[2]
        // Whole pixels, so a little off 731 : 14 : 124.
        assertEquals(731f / 124f, twoYears / fourMonths, 0.2f)
        assertEquals(731f / 14f, twoYears / twoWeeks, 8f)
    }

    @Test
    fun aFingerSelectsTheReadingUnderItByDate() {
        trendChart()
        val third = compose.onNodeWithContentDescription(readingLabel(irregularVitaminD[2].date, "33 ng/mL")).fetchSemanticsNode().boundsInRoot
        val chart = compose.onNode(hasContentDescription(" chart: ", substring = true))
        val origin = chart.fetchSemanticsNode().boundsInRoot
        // By index the third of four readings sits two thirds along; by date, 86 % along.
        chart.performTouchInput { down(Offset(third.center.x - origin.left, height / 2f)) }
        compose.onNodeWithText("33").assertExists()
        chart.performTouchInput { up() }
    }

    @Test
    fun cardsNameValuesPlainly() {
        compose.setContent { LabImporterTheme { DashboardScreen(irregularVitaminD, LabDisplayPreferences(), {}, ImportActions({}, {}, {}, {}, {})) } }
        // Breakable after its hyphen: Android cuts the word at the edge otherwise.
        compose.onAllNodesWithText("25-\u200Bhydroxyvitamin D3").onFirst().assertExists()
        compose.onAllNodesWithText("[", substring = true).assertCountEquals(0)
    }

    @Test
    fun reviewOffersTheNameFoundInTheReport() {
        var draft by mutableStateOf(
            ReviewDraft(listOf(value("a", "2160-0", 0.8)), LocalDate(2026, 5, 6), "", "", AiEngine.GEMINI_NANO, detectedPatientName = "Erika Muster"),
        )
        compose.setContent { LabImporterTheme { ReviewScreen(draft, { draft = it(draft) }, { _, _ -> }, { "" }, {}, {}, {}) } }
        val suggestion = app.getString(R.string.detected_in_report, "Erika Muster")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(suggestion))
        compose.onNodeWithText(string(R.string.use)).performClick()
        assertEquals("Erika Muster", draft.patientName)
        // Once used, the suggestion goes away.
        compose.onNodeWithText(suggestion).assertDoesNotExist()
    }
}
