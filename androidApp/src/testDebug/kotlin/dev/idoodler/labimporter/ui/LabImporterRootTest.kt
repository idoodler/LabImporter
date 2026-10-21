package dev.idoodler.labimporter.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiException
import dev.idoodler.labimporter.ai.AiProblem
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.ai.FakeAi
import dev.idoodler.labimporter.ai.UnsupportedReason
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The app shell: the AI gate (like iOS), onboarding, and the dashboard's entry points. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "w411dp-h914dp")
class LabImporterRootTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TestApplication get() = ApplicationProvider.getApplicationContext()
    private val ai = FakeAi()
    private val standIn = FakeAi()

    @Before
    fun setUp() {
        TestApplication.ai = ai
        TestApplication.standIn = standIn
    }

    private fun string(id: Int) = app.getString(id)

    /** Waits for [condition], letting main-thread and background work (the shared pipeline) run. */
    private fun waitFor(condition: () -> Boolean) = compose.waitFor(condition)

    private fun launch(onboarded: Boolean = true): AppViewModel {
        app.getSharedPreferences("labimporter", Context.MODE_PRIVATE).edit().putBoolean("hasSeenWelcome", onboarded).commit()
        val viewModel = AppViewModel(app)
        compose.setContent { LabImporterTheme { LabImporterRoot(viewModel) } }
        return viewModel
    }

    @Test
    fun withoutOnDeviceAiTheAppOnlyExplainsWhy() {
        ai.state.value = AiAvailability.Unsupported(UnsupportedReason.DEVICE)
        launch()
        compose.onNodeWithText(string(R.string.unsupported_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.unsupported_message)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.dashboard_empty_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.import_title)).assertDoesNotExist()
    }

    @Test
    fun checkAgainAsksTheSystemAndOpensTheAppOnceTheModelIsThere() {
        ai.state.value = AiAvailability.Unsupported(UnsupportedReason.DEVICE)
        launch()
        compose.waitForIdle()
        val before = ai.refreshes // Resuming the app already re-checks once.
        ai.onRefresh = { state.value = AiAvailability.Ready }
        compose.onNodeWithText(string(R.string.check_again)).performClick()
        waitFor { ai.refreshes > before }
        compose.onNodeWithText(string(R.string.import_title)).assertIsDisplayed()
    }

    @Test
    fun anOutdatedSystemAsksForAnUpdate() {
        ai.state.value = AiAvailability.Unsupported(UnsupportedReason.UPDATE_REQUIRED)
        launch()
        compose.onNodeWithText(string(R.string.unsupported_update_message)).assertIsDisplayed()
    }

    @Test
    fun debugBuildsCanContinueWithTheDevelopmentStandIn() {
        ai.state.value = AiAvailability.Unsupported(UnsupportedReason.DEVICE)
        launch(onboarded = false)
        compose.onNodeWithText(checkNotNull(DevelopmentStandIn.texts).use).performClick()
        compose.onNodeWithText(string(R.string.get_started)).assertIsDisplayed()
    }

    @Test
    fun theEmptyDashboardsSettingsButtonOpensSettings() {
        // Regression: on Android 16 the button sat under the scrolling content and didn't react.
        launch()
        compose.onNodeWithContentDescription(string(R.string.tab_settings)).performClick()
        compose.onNodeWithText(string(R.string.ai_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.ai_available)).assertIsDisplayed()
    }

    @Test
    fun aModelProblemIsShownWithItsReason() {
        ai.reply = { throw AiException(AiProblem.BACKGROUND) }
        val viewModel = launch()
        compose.runOnIdle { viewModel.importText("KREA 0,8") }
        compose.onNodeWithText(string(R.string.import_failed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.ai_problem_background)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.ok)).performClick()
        compose.runOnIdle { assertEquals(ImportState.Idle, viewModel.importState.value) }
    }

    @Test
    fun aReadReportOpensTheReviewScreen() {
        ai.reply = { FakeAi.replyJson(listOf("2160-0", "Kreatinin", "0,8", "mg/dl")) }
        val viewModel = launch()
        compose.runOnIdle { viewModel.importText("Kreatinin 0,8 mg/dl") }
        waitFor { viewModel.importState.value.let { it is ImportState.Reviewing || it is ImportState.Failed } }
        assertEquals(ImportState.Reviewing::class, viewModel.importState.value::class)
        compose.onNodeWithText(string(R.string.review_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.engine_gemini)).assertIsDisplayed()
    }
}
