package dev.idoodler.labimporter.ui

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ai.FakeAi
import dev.idoodler.labimporter.core.model.Dashboard
import dev.idoodler.labimporter.core.transfer.ReportTransfer
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A Function Health–style report end to end on Android: read in parts by a
 * scripted model (the way the real one reads it — status labels, a flag and
 * a section heading included), the shared post-processing with the real
 * LOINC catalog, review, saving, the dashboard, the trend screen and the
 * iPhone ⇄ Android transfer file. The iOS counterpart is `EndToEndTests`.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "w411dp-h914dp")
class EndToEndTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TestApplication get() = ApplicationProvider.getApplicationContext()
    private val ai = FakeAi()

    @Before
    fun setUp() {
        TestApplication.ai = ai
        TestApplication.standIn = null
    }

    /** (printed name, value as the model copies it, unit) — the rows of the report. */
    private val rows = listOf(
        Triple("Uric Acid", "In Range· 5.1", "mg/dL"),
        Triple("Carbon Dioxide", "24", "mmol/L"),
        Triple("Platelet Count", "263", "Thousand/uL"),
        Triple("Thyroid-Stimulating Hormone (TSH)", "1.9", "mIU/L"),
        Triple("Lead", "In Range· <1.0", "mcg/dL"),
        Triple("Celiac – Deamidated Gliadin IgA", "4.5 *", "U/mL"),
        Triple("HEPATITIS PANEL", "NON-REACTIVE", ""),
        Triple("Hepatitis B Surface Antigen", "NON-REACTIVE", ""),
    )

    /** The report, long enough to be read in more than one part. */
    private val report = buildList {
        add("Function Health · Quest Diagnostics\nPatient: Jane Roe     DOB: 02/11/1984\nCollected: 08/12/2026")
        rows.take(4).forEach { (name, value, unit) -> add("${name.padEnd(34)} $value $unit".trim()) }
        repeat(24) { add("Note ${it + 1}: This marker reflects how your body is doing over time; discuss changes with your clinician.") }
        rows.drop(4).forEach { (name, value, unit) -> add(if (name == "HEPATITIS PANEL") name else "${name.padEnd(34)} $value $unit".trim()) }
    }.joinToString("\n")

    /** What the model reads from the part it's given: every row printed there. */
    private fun readPart(): String {
        val part = ai.requests.last().second
        val read = rows.filter { (name) -> name in part }.map { (name, value, unit) -> listOf(name, name, value, unit) }
        return FakeAi.replyJson(*read.toTypedArray(), reportDate = "2026-08-12", patientName = "Jane Roe",
            authorName = "Function Health", language = "en")
    }

    @Test
    fun aFunctionHealthReportFromImportToTheDashboardAndTheTransferFile() {
        ai.reply = { readPart() }
        app.getSharedPreferences("labimporter", Context.MODE_PRIVATE).edit().putBoolean("hasSeenWelcome", true).commit()
        val viewModel = AppViewModel(app)
        compose.setContent { LabImporterTheme { LabImporterRoot(viewModel) } }

        // Read in parts, then review.
        compose.runOnIdle { viewModel.importText(report) }
        compose.waitFor { viewModel.importState.value.let { it is ImportState.Reviewing || it is ImportState.Failed } }
        val draft = (viewModel.importState.value as ImportState.Reviewing).draft
        assertTrue("read in parts", ai.requests.size > 1)
        assertEquals(LocalDate(2026, 8, 12), draft.date)
        val values = draft.values
        fun value(code: String) = values.firstOrNull { it.code == code } ?: error("$code not in ${values.map { it.code }}")
        assertEquals(5.1, value("3084-1").numericValue)     // "In Range· 5.1"
        assertEquals(24.0, value("2028-9").numericValue)    // Carbon Dioxide → total CO₂
        assertEquals(263.0, value("777-3").numericValue)    // Thousand/uL is a count
        assertEquals(1.9, value("3016-3").numericValue)
        assertEquals(4.5, value("58709-7").numericValue)    // "4.5 *", the gliadin peptide IgA antibody
        val lead = value("77307-7")                         // venous lead, "<1.0" kept as printed
        assertNull(lead.numericValue)
        assertEquals("<1.0", lead.qualitativeResult)
        val hbsAg = values.single { it.qualitativeResult == "NON-REACTIVE" && it.isExportable }
        assertFalse("a section heading is never saved", values.single { it.name.equals("Hepatitis Panel", true) }.isExportable)
        assertEquals(7, values.count { it.isExportable })

        // Saved; the dashboard charts the numbers and lists the results that aren't numbers.
        compose.runOnIdle { viewModel.saveDraft() }
        compose.waitFor { viewModel.reports.value.isNotEmpty() && viewModel.importState.value == ImportState.Idle }
        val saved = viewModel.reports.value.single()
        assertEquals(7, saved.entries.size)
        assertEquals("<1.0", saved.entries.single { it.code == "77307-7" }.displayValue)
        val metrics = Dashboard.dashboardMetrics(viewModel.reports.value)
        assertEquals(setOf("77307-7", hbsAg.code), metrics.filter { it.isQualitative }.map { it.code }.toSet())
        val grid = compose.onAllNodes(hasScrollAction()).onFirst()
        grid.performScrollToNode(hasText("<1.0", substring = true))
        grid.performScrollToNode(hasText("NON-REACTIVE"))

        // A result that isn't a number opens its list of results.
        compose.runOnIdle { viewModel.request(LaunchRequest.OpenMetric(hbsAg.code)) }
        compose.waitFor { compose.onAllNodesWithText(app.getString(R.string.readings)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("NON-REACTIVE").fetchSemanticsNodes().isNotEmpty())

        // The transfer file carries every value and is recognized again.
        val file = ReportTransfer.export(viewModel.reports.value, "LabImporter test")
        val read = ReportTransfer.read(file) as ReportTransfer.ReadResult.Reports
        assertEquals(saved.entries.map { it.code }.toSet(), read.reports.single().entries.map { it.code }.toSet())
        assertEquals(1, ReportTransfer.plan(viewModel.reports.value, read.reports).alreadyPresent)
    }
}
