package dev.idoodler.labimporter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.AiProblem
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.ui.review.ReviewScreen
import dev.idoodler.labimporter.ui.settings.AiSettings
import dev.idoodler.labimporter.ui.settings.SettingsScreen
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlinx.datetime.LocalDate
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
                SettingsScreen("", {}, HealthConnectState(), {}, {}, ai, LabDisplayPreferences(), {}, "LabImporter test")
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
}
