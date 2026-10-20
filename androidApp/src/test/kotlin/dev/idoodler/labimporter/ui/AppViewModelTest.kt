package dev.idoodler.labimporter.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.AiException
import dev.idoodler.labimporter.ai.AiProblem
import dev.idoodler.labimporter.ai.FakeAi
import dev.idoodler.labimporter.ai.UnsupportedReason
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The import flow, end to end on the JVM: text → model → shared pipeline → review → saved report. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class AppViewModelTest {
    private val app: TestApplication get() = ApplicationProvider.getApplicationContext()
    private val ai = FakeAi()
    private lateinit var viewModel: AppViewModel

    @Before
    fun setUp() {
        TestApplication.ai = ai
        TestApplication.standIn = null
        viewModel = AppViewModel(app)
    }

    /** Runs main-thread work (and waits for background work) until [flow] satisfies [predicate]. */
    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            idle()
            val value = flow.value
            if (predicate(value)) return value
            check(System.currentTimeMillis() < deadline) { "Timed out; last value: $value" }
            Thread.sleep(10)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Changes what the model reports and lets the app observe it. */
    private fun setAvailability(availability: AiAvailability) {
        ai.state.value = availability
        await(viewModel.aiAvailability) { it == availability }
    }

    private fun failure(): String = (await(viewModel.importState) { it is ImportState.Failed } as ImportState.Failed).message

    @Test
    fun aReadReportOpensForReviewWithResolvedLoincCodes() {
        ai.reply = {
            FakeAi.replyJson(
                listOf("KREA", "Kreatinin", "0,8", "mg/dl"),
                listOf("HB-A1C", "HbA1c", "6,5", "%"),
            )
        }
        viewModel.importText("KREA: 0,8 mg/dl; HB-A1C: 6,5 %")
        val draft = (await(viewModel.importState) { it is ImportState.Reviewing } as ImportState.Reviewing).draft
        assertEquals(AiEngine.GEMINI_NANO, draft.engine)
        assertEquals(LocalDate(2026, 5, 6), draft.date)
        assertEquals("Max Mustermann", draft.patientName)
        // Resolved in the report's own language by the shared catalog search, offered as suggestions.
        assertTrue(draft.values.all { it.isSuggestedCode && Regex("^\\d+-\\d$").matches(it.code) })
        assertEquals(listOf(0.8, 6.5), draft.values.map { it.numericValue })
    }

    @Test
    fun savingAReviewedReportStoresItAndShowsItOnTheDashboard() {
        ai.reply = { FakeAi.replyJson(listOf("2160-0", "Kreatinin", "0,8", "mg/dl")) }
        viewModel.importText("Kreatinin 0,8 mg/dl")
        await(viewModel.importState) { it is ImportState.Reviewing }
        viewModel.saveDraft()
        val reports = await(viewModel.reports) { it.isNotEmpty() }
        assertEquals(listOf("2160-0"), reports.single().entries.map { it.code })
        assertEquals(ImportState.Idle, viewModel.importState.value)
    }

    @Test
    fun aReportTooLongForTheModelSaysSoLikeIos() {
        ai.reply = { throw AiException(AiProblem.DOCUMENT_TOO_LONG) }
        viewModel.importText("…")
        assertEquals(app.getString(R.string.ai_problem_too_long), failure())
    }

    @Test
    fun aReplyWithoutValuesIsReportedNotSaved() {
        ai.reply = { FakeAi.replyJson() }
        viewModel.importText("Befund ohne Werte")
        assertEquals(app.getString(R.string.error_no_values), failure())
    }

    @Test
    fun blankTextNeverReachesTheModel() {
        viewModel.importText("   ")
        assertEquals(app.getString(R.string.error_no_text), failure())
        assertTrue(ai.requests.isEmpty())
    }

    @Test
    fun anImportWaitsForTheModelDownloadAndThenContinues() {
        setAvailability(AiAvailability.Preparing(10, 100))
        ai.reply = { FakeAi.replyJson(listOf("2160-0", "Kreatinin", "0,8", "mg/dl")) }
        viewModel.importText("Kreatinin 0,8 mg/dl")
        val preparing = await(viewModel.importState) { it is ImportState.PreparingModel } as ImportState.PreparingModel
        assertEquals(0.1f, preparing.preparing.fraction)
        setAvailability(AiAvailability.Ready)
        await(viewModel.importState) { it is ImportState.Reviewing }
    }

    @Test
    fun aFailedDownloadIsRetriedOnImportAndExplainedIfItFailsAgain() {
        setAvailability(AiAvailability.PreparationFailed(AiProblem.NOT_ENOUGH_STORAGE))
        ai.onRefresh = { state.value = AiAvailability.PreparationFailed(AiProblem.NOT_ENOUGH_STORAGE) }
        viewModel.importText("Kreatinin 0,8 mg/dl")
        assertEquals(app.getString(R.string.ai_problem_storage), failure())
        assertEquals(1, ai.refreshes)
    }

    @Test
    fun anUnsupportedDeviceIsCheckedAgainOnResume() {
        setAvailability(AiAvailability.Unsupported(UnsupportedReason.DEVICE))
        viewModel.refresh()
        await(viewModel.aiAvailability) { ai.refreshes > 0 }
    }
}
