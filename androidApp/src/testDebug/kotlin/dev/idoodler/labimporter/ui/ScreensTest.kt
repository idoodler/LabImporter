package dev.idoodler.labimporter.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.AiProblem
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.core.loinc.LoincTerm
import dev.idoodler.labimporter.core.model.CodeName
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.data.PatientDetails
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
        compose.onNodeWithText(string(R.string.engine_gemini)).assertDoesNotExist()
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
    fun reviewOffersTheNameFoundInTheReport() {
        var draft by mutableStateOf(
            ReviewDraft(listOf(value("a", "2160-0", 0.8)), LocalDate(2026, 5, 6), "", "", AiEngine.GEMINI_NANO, detectedPatientName = "Erika Muster"),
        )
        compose.setContent { LabImporterTheme { ReviewScreen(draft, { draft = it(draft) }, { _, _ -> }, { "" }, {}, {}, {}) } }
        val suggestion = app.getString(R.string.detected_in_report, "Erika Muster")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(suggestion))
        compose.onNodeWithText(string(R.string.use)).performClick()
        assertEquals("Erika Muster", draft.patientName)
        // Once used, the suggestion goes away.
        compose.onNodeWithText(suggestion).assertDoesNotExist()
    }
}
