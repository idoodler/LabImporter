package dev.idoodler.labimporter.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ai.FakeAi
import dev.idoodler.labimporter.ai.gemma.GemmaModel
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What Review does to stop a wrong value, through the real shared post-processing
 * and LOINC catalog with a scripted model. The iOS counterpart is `EndToEndUITests`.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "w411dp-h914dp")
class ReviewCheckTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TestApplication get() = ApplicationProvider.getApplicationContext()
    private val ai = FakeAi()
    private val defaultReport = "Labor Dr. Muster, Befund vom 06.05.2026\nKreatinin 0,8 mg/dl\nALT 22 U/l"
    private fun string(id: Int) = app.getString(id)
    private val detail get() = string(R.string.compare_with_report_detail)
    private fun toCheck(count: Int) = app.resources.getQuantityString(R.plurals.values_to_check, count, count)
    private fun correct(count: Int) = app.resources.getQuantityString(R.plurals.values_to_correct, count, count)

    @Before
    fun setUp() {
        TestApplication.ai = ai
        TestApplication.standIn = null
    }

    private fun review(report: String = defaultReport, reply: () -> String): AppViewModel {
        ai.reply = reply
        app.getSharedPreferences("labimporter", Context.MODE_PRIVATE).edit().putBoolean("hasSeenWelcome", true).commit()
        val viewModel = AppViewModel(app)
        compose.setContent { LabImporterTheme { LabImporterRoot(viewModel) } }
        compose.runOnIdle { viewModel.importText(report) }
        compose.waitFor { viewModel.importState.value.let { it is ImportState.Reviewing || it is ImportState.Failed } }
        return viewModel
    }

    private val AppViewModel.draft get() = (importState.value as ImportState.Reviewing).draft

    /** ALT 22 read as 222, as evaluation/README.md records. */
    private fun misread(reportDate: String = "2026-05-06") = FakeAi.replyJson(
        listOf("2160-0", "Kreatinin", "0,8", "mg/dl"),
        listOf("1742-6", "ALT", "222", "U/l"),
        reportDate = reportDate,
    )

    @Test
    fun aValueTheReportDoesNotPrintIsFlaggedWithoutBlockingSaving() {
        val viewModel = review { misread() }
        assertEquals(listOf(false, true), viewModel.draft.values.map { it.needsSourceCheck })

        compose.onNodeWithText(toCheck(1)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.ready_to_save)).assertDoesNotExist()
        compose.onAllNodesWithText(string(R.string.save)).onFirst().assertIsEnabled()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(string(R.string.compare_with_report)))
        compose.onNodeWithText(string(R.string.compare_with_report)).assertIsDisplayed()
        // TalkBack hears it on the badge and on the value itself.
        compose.onNodeWithContentDescription(detail).assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, detail) and hasText("222")).assertExists()

        // Correcting the value clears the badge and the count.
        compose.onNode(hasText("222")).performTextReplacement("22")
        compose.waitForIdle()
        assertFalse(viewModel.draft.values.any { it.needsSourceCheck })
        assertEquals(22.0, viewModel.draft.values.last().numericValue)
        compose.onNodeWithText(string(R.string.compare_with_report)).assertDoesNotExist()
        compose.onNodeWithText(toCheck(1)).assertDoesNotExist()
    }

    @Test
    fun aValueTheReportPrintsIsNotFlagged() {
        val viewModel = review { FakeAi.replyJson(listOf("2160-0", "Kreatinin", "0.8", "mg/dl"), listOf("1742-6", "ALT", "22", "U/l")) }
        assertFalse(viewModel.draft.values.any { it.needsSourceCheck })
        compose.onNodeWithText(string(R.string.ready_to_save)).assertIsDisplayed()
    }

    @Test
    fun aTypedResultThatIsNoNumberIsNotSavedAsOne() {
        val viewModel = review { misread() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("222"))
        compose.onNode(hasText("222")).performTextReplacement("Infinity")
        compose.waitForIdle()
        assertEquals(null, viewModel.draft.values.last().numericValue)
    }

    /** Seeded "263,000", an edit to "263,500" was saved as 263.5 without a word. */
    @Test
    fun editingAThousandsValueKeepsItsSize() {
        val viewModel = review("Thrombozyten 263,000 /ul\nKreatinin 0.8 mg/dl") {
            FakeAi.replyJson(listOf("777-3", "Thrombozyten", "263,000", "/ul"), listOf("2160-0", "Kreatinin", "0.8", "mg/dl"))
        }
        val platelets = { viewModel.draft.values.first { it.code == "777-3" } }
        assertEquals(263000.0, platelets().numericValue)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("263000"))
        compose.onNode(hasText("263000")).performTextReplacement("263500")
        compose.waitForIdle()
        assertEquals(263500.0, platelets().numericValue)
        assertFalse(platelets().needsCorrection)
    }

    @Test
    fun aSavedReportsValueIsOfferedForEditingWithoutGrouping() {
        val saved = LabReport(
            id = "r1", date = LocalDate(2026, 5, 6), patientName = "", authorName = "",
            entries = listOf(LabReport.Entry(id = "e1", code = "777-3", name = "Platelets", displayValue = "263000", numericValue = 263000.0, unit = "/ul")),
        )
        app.getSharedPreferences("labimporter", Context.MODE_PRIVATE).edit().putBoolean("hasSeenWelcome", true).commit()
        val viewModel = AppViewModel(app)
        compose.setContent { LabImporterTheme { LabImporterRoot(viewModel) } }
        compose.runOnIdle { viewModel.editReport(saved) }
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("263000"))
        compose.onNode(hasText("263000")).assertExists()
    }

    @Test
    fun aResultThatCannotBeSavedHoldsBackSavingAndSaysSo() {
        val viewModel = review { misread() }
        val save = compose.onAllNodesWithText(string(R.string.save)).onFirst()
        save.assertIsEnabled()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("222"))
        compose.onNode(hasText("222")).performTextReplacement("neg 1")
        compose.waitForIdle()
        assertEquals(listOf(false, true), viewModel.draft.values.map { it.needsCorrection })
        save.assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.ready_to_save)).assertDoesNotExist()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(string(R.string.correct_or_deselect)))
        compose.onNodeWithText(string(R.string.correct_or_deselect)).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(correct(1)))
        compose.onNodeWithText(correct(1)).assertIsDisplayed()

        // Deselecting is an answer too.
        compose.runOnIdle { viewModel.updateValue(viewModel.draft.values.last().id) { it.copy(isSelected = false) } }
        compose.waitForIdle()
        save.assertIsEnabled()
        compose.onNodeWithText(correct(1)).assertDoesNotExist()
    }

    @Test
    fun aMissingDateIsSaidUntilTheUserChangesIt() {
        val viewModel = review { misread(reportDate = "") }
        assertTrue(viewModel.draft.dateNotFound)
        compose.onNodeWithText(string(R.string.no_date_found)).assertIsDisplayed()

        // OK on the same date changes nothing.
        val shown = viewModel.draft.date
        compose.runOnIdle { viewModel.updateDraft { it.withDate(shown) } }
        compose.onNodeWithText(string(R.string.no_date_found)).assertIsDisplayed()

        compose.runOnIdle { viewModel.updateDraft { it.withDate(LocalDate(2026, 5, 6)) } }
        compose.waitForIdle()
        assertFalse(viewModel.draft.dateNotFound)
        compose.onNodeWithText(string(R.string.no_date_found)).assertDoesNotExist()
    }

    @Test
    fun aDateTheReportPrintsIsNotQuestioned() {
        val viewModel = review { misread() }
        assertFalse(viewModel.draft.dateNotFound)
        compose.onNodeWithText(string(R.string.no_date_found)).assertDoesNotExist()
    }

    @Test
    fun manualEntryHasNothingToCompareWith() {
        app.getSharedPreferences("labimporter", Context.MODE_PRIVATE).edit().putBoolean("hasSeenWelcome", true).commit()
        val viewModel = AppViewModel(app)
        compose.setContent { LabImporterTheme { LabImporterRoot(viewModel) } }
        compose.runOnIdle { viewModel.startManualEntry() }
        compose.waitForIdle()
        assertFalse(viewModel.draft.dateNotFound)
        assertFalse(viewModel.draft.values.any { it.needsSourceCheck })
        compose.onNodeWithText(string(R.string.no_date_found)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun theFirstValueIsOnScreenOnASmallPhoneWithoutScrolling() {
        val viewModel = review { misread() }
        compose.onNodeWithText(viewModel.draft.values.first().resolvedName).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.lab_or_doctor)).assertDoesNotExist()
        compose.onNodeWithText(GemmaModel.NAME).assertDoesNotExist()
    }

    @Test
    fun patientAndLabFoldAwayAfterTheValuesWithTheirSuggestionsInView() {
        val viewModel = review { misread() }
        val details = compose.onNode(hasScrollToNodeAction())
        details.performScrollToNode(hasText(string(R.string.patient_and_lab)))
        val offered = app.getString(R.string.detected_in_report, "Max Mustermann")
        details.performScrollToNode(hasText(offered))
        compose.onNodeWithText(string(R.string.patient)).assertDoesNotExist()

        compose.onNodeWithText(string(R.string.patient_and_lab)).performClick()
        details.performScrollToNode(hasText(string(R.string.patient)))
        compose.onNodeWithText(string(R.string.patient)).assertExists()

        // Taking up the suggestion fills in the summary line.
        details.performScrollToNode(hasText(offered))
        compose.onAllNodesWithText(string(R.string.use))[1].performClick()
        compose.waitForIdle()
        assertEquals("Max Mustermann", viewModel.draft.patientName)
        compose.onNodeWithText(offered).assertDoesNotExist()
        details.performScrollToNode(hasText("Max Mustermann", substring = true))
    }
}
