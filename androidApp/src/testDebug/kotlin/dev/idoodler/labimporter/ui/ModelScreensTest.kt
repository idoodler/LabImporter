package dev.idoodler.labimporter.ui

import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import dev.idoodler.labimporter.ai.PreparingStage
import dev.idoodler.labimporter.ai.UnsupportedReason
import dev.idoodler.labimporter.ai.gemma.GemmaModel
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.ui.components.ModelStatusCard
import dev.idoodler.labimporter.ui.onboarding.UnsupportedDeviceScreen
import dev.idoodler.labimporter.ui.review.ReviewScreen
import dev.idoodler.labimporter.ui.settings.AiSettings
import dev.idoodler.labimporter.ui.settings.PatientSettings
import dev.idoodler.labimporter.ui.settings.SettingsScreen
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Gemma download as the user meets it: the offer, Settings, the dashboard and review. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "w411dp-h914dp")
class ModelScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TestApplication get() = ApplicationProvider.getApplicationContext()
    private fun string(id: Int, vararg args: Any) = app.getString(id, *args)
    private val size get() = Formatter.formatShortFileSize(app, GemmaModel.SIZE_BYTES)

    @Test
    fun theOfferSaysSizeWhatIsSentAndTheLicenseBeforeDownloading() {
        val downloads = mutableListOf<Boolean>()
        compose.setContent {
            LabImporterTheme {
                UnsupportedDeviceScreen(
                    UnsupportedReason.DEVICE, checking = false, onCheckAgain = {}, detail = "Prompt API: UNAVAILABLE",
                    modelDownload = true, onDownloadModel = { downloads += it },
                )
            }
        }
        compose.onNodeWithText(string(R.string.model_offer_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.model_fact_size_title, size)).assertExists()
        compose.onNodeWithText(string(R.string.model_fact_privacy_detail, GemmaModel.HOST)).assertExists()
        compose.onNodeWithText(string(R.string.model_fact_license_detail, GemmaModel.LICENSE)).assertExists()
        // The reason Gemini Nano isn't used stays visible.
        compose.onNodeWithText("${string(R.string.details)}: Prompt API: UNAVAILABLE").assertExists()

        compose.onNodeWithText(string(R.string.model_download, size)).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(string(R.string.model_allow_mobile_data)))
        compose.onNodeWithText(string(R.string.model_allow_mobile_data)).performClick()
        compose.onNodeWithText(string(R.string.model_download, size)).performClick()
        // Wi-Fi only unless the user allows mobile data.
        assertEquals(listOf(false, true), downloads)
    }

    @Test
    fun withoutAnOfferTheScreenStaysTheUnsupportedOne() {
        compose.setContent { LabImporterTheme { UnsupportedDeviceScreen(UnsupportedReason.DEVICE, checking = false, onCheckAgain = {}) } }
        compose.onNodeWithText(string(R.string.unsupported_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.model_download, size)).assertDoesNotExist()
    }

    private fun settings(ai: AiSettings) {
        compose.setContent { LabImporterTheme { SettingsScreen(PatientSettings(), HealthConnectState(), {}, {}, ai, "LabImporter test") } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(string(R.string.model_settings_title)))
    }

    @Test
    fun settingsLetUsersDeleteTheModelAfterAsking() {
        var removed = 0
        settings(AiSettings(AiAvailability.Ready, engine = AiEngine.GEMMA, modelInstalled = true, onRemoveModel = { removed++ }))
        compose.onNodeWithText(GemmaModel.NAME).assertExists()
        compose.onNodeWithText(string(R.string.ai_available_gemma)).assertExists()
        compose.onNodeWithText(string(R.string.model_delete)).performClick()
        assertEquals(0, removed)
        compose.onNodeWithText(string(R.string.model_delete_message, size)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.delete)).performClick()
        assertEquals(1, removed)
    }

    @Test
    fun settingsShowTheWaitForWifiAndCanCancel() {
        var mobileData = 0
        var removed = 0
        settings(
            AiSettings(
                AiAvailability.Preparing(0, GemmaModel.SIZE_BYTES, PreparingStage.WAITING_FOR_WIFI), engine = AiEngine.GEMMA,
                onUseMobileData = { mobileData++ }, onRemoveModel = { removed++ },
            ),
        )
        compose.onNodeWithText(string(R.string.model_waiting_wifi)).assertExists()
        compose.onNodeWithText(string(R.string.model_use_mobile_data)).performClick()
        compose.onNodeWithText(string(R.string.model_cancel_download)).performClick()
        assertEquals(1, mobileData)
        assertEquals(1, removed) // Cancelling a download needs no confirmation.
    }

    @Test
    fun settingsSayWhenTheModelIsNoLongerNeeded() {
        settings(AiSettings(AiAvailability.Ready, engine = AiEngine.GEMINI_NANO, modelInstalled = true))
        compose.onNodeWithText(string(R.string.model_not_needed)).assertExists()
        compose.onNodeWithText(string(R.string.model_delete)).performClick()
        compose.onNodeWithText(string(R.string.model_delete_message_unneeded, size)).assertIsDisplayed()
    }

    @Test
    fun theDashboardCardShowsProgressAndFailures() {
        var retries = 0
        var availability: AiAvailability by mutableStateOf(AiAvailability.Preparing(1_000_000_000, GemmaModel.SIZE_BYTES))
        compose.setContent { LabImporterTheme { ModelStatusCard(availability, AiEngine.GEMMA, onTryAgain = { retries++ }, onUseMobileData = {}) } }
        compose.onNodeWithText(string(R.string.processing_downloading, GemmaModel.NAME)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.model_progress, Formatter.formatShortFileSize(app, 1_000_000_000), size)).assertIsDisplayed()

        availability = AiAvailability.Preparing(GemmaModel.SIZE_BYTES, GemmaModel.SIZE_BYTES, PreparingStage.VERIFYING)
        compose.onNodeWithText(string(R.string.model_verifying)).assertIsDisplayed()

        availability = AiAvailability.PreparationFailed(AiProblem.DOWNLOAD_FAILED)
        compose.onNodeWithText(string(R.string.ai_problem_download)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.try_again)).performClick()
        assertEquals(1, retries)

        availability = AiAvailability.Ready
        compose.onNodeWithText(string(R.string.try_again)).assertDoesNotExist()
    }

    @Test
    fun reviewSaysGemmaReadTheReport() {
        val value = LabValue(id = "a", code = "2160-0", name = "Creatinine", displayValue = "0.8", numericValue = 0.8, unit = "mg/dl")
        val draft = ReviewDraft(listOf(value), LocalDate(2026, 5, 6), "", "", AiEngine.GEMMA)
        compose.setContent { LabImporterTheme { ReviewScreen(draft, {}, { _, _ -> }, { "" }, {}, {}, {}) } }
        compose.onNodeWithText(GemmaModel.NAME).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.engine_gemini)).assertDoesNotExist()
    }
}
