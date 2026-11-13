package dev.idoodler.labimporter.ui

import android.net.Uri
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.LabImporterApplication
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.AiException
import dev.idoodler.labimporter.ai.AiProblem
import dev.idoodler.labimporter.ai.FakeAi
import dev.idoodler.labimporter.ai.UnsupportedReason
import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.export.CdaNarrative
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.export.ReportSchema
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.transfer.ReportTransfer
import dev.idoodler.labimporter.data.LocalReportStore
import dev.idoodler.labimporter.data.StorageMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The import flow, end to end on the JVM: text → model → shared pipeline → review → saved report. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class AppViewModelTest {
    private val app: TestApplication get() = ApplicationProvider.getApplicationContext()
    private val ai = FakeAi()
    private lateinit var viewModel: AppViewModel

    @Before
    fun setUp() {
        // FileProvider keeps the roots it first saw in a static cache; each test has a new data directory.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
        TestApplication.ai = ai
        TestApplication.standIn = null
        viewModel = AppViewModel(app)
    }

    /**
     * Runs main-thread work (and waits for background work) until [flow] satisfies [predicate].
     * The deadline only matters when the test fails; shared CI runners need far longer than a Mac.
     */
    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + 30_000
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
        // A name read from the report is only offered (as on iOS); the field keeps the remembered one.
        assertEquals("", draft.patientName)
        assertEquals("Max Mustermann", draft.detectedPatientName)
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
    fun namesUsedForANewReportAreFilledInForTheNext() {
        ai.reply = { FakeAi.replyJson(listOf("2160-0", "Kreatinin", "0,8", "mg/dl")) }
        viewModel.importText("Kreatinin 0,8 mg/dl")
        await(viewModel.importState) { it is ImportState.Reviewing }
        viewModel.updateDraft { it.copy(patientName = it.detectedPatientName.orEmpty(), authorName = "Labor Dr. Muster") }
        viewModel.saveDraft()
        await(viewModel.reports) { it.isNotEmpty() }

        viewModel.importText("Kreatinin 0,9 mg/dl")
        val next = (await(viewModel.importState) { it is ImportState.Reviewing } as ImportState.Reviewing).draft
        assertEquals("Max Mustermann", next.patientName)
        assertEquals("Labor Dr. Muster", next.authorName)
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

    private fun file(name: String, contents: String): Uri =
        Uri.fromFile(File(app.cacheDir, name).apply { writeText(contents) })

    @Test
    fun aTransferFileImportsOnceAndSkipsWhatIsAlreadyHere() {
        ai.reply = { FakeAi.replyJson(listOf("2160-0", "Kreatinin", "0,8", "mg/dl")) }
        viewModel.importText("Kreatinin 0,8 mg/dl")
        await(viewModel.importState) { it is ImportState.Reviewing }
        viewModel.saveDraft()
        await(viewModel.reports) { it.size == 1 }
        await(viewModel.message) { it != null } // "Saved 1 value"
        viewModel.consumeMessage()

        assertTrue(viewModel.exportAllReports() is ReportsExport.Ready)
        val exported = File(app.cacheDir, "exports").listFiles().orEmpty().single { it.extension == "json" }
        viewModel.importReports(Uri.fromFile(exported))
        assertEquals(app.getString(R.string.transfer_summary, 0, 1), await(viewModel.message) { it != null })
        assertEquals(1, viewModel.reports.value.size)
    }

    @Test
    fun aCdaDocumentFromTheOtherAppImports() {
        val values = listOf(LabValue(code = "4548-4", name = "HbA1c", displayValue = "6,5", numericValue = 6.5, unit = "%"))
        val cda = CdaExport.generate(values, LocalDate(2026, 3, 1), "LabImporter iOS", narrative)
        viewModel.importReports(file("report.xml", cda))
        assertEquals(app.getString(R.string.transfer_summary, 1, 0), await(viewModel.message) { it != null })
        assertEquals(listOf("4548-4"), await(viewModel.reports) { it.isNotEmpty() }.single().entries.map { it.code })
    }

    @Test
    fun aForeignFileIsRejectedNotGuessedAt() {
        viewModel.importReports(file("other.json", "{\"resourceType\":\"Patient\"}"))
        assertEquals(app.getString(R.string.transfer_unsupported), await(viewModel.message) { it != null })
        assertTrue(viewModel.reports.value.isEmpty())
    }

    private val narrative = CdaNarrative("en", "Lab Results", "Lab Values", "Test", "Result")

    private val cda: String
        get() = CdaExport.generate(
            listOf(LabValue(code = "4548-4", name = "HbA1c", displayValue = "6,5", numericValue = 6.5, unit = "%")),
            LocalDate(2026, 3, 1), "LabImporter iOS", narrative,
        )

    /** The real store's directory is gone and can't be made again: a full or failing disk. */
    private fun breakTheReportStore() {
        val directory = File(app.noBackupFilesDir, "reports")
        directory.deleteRecursively()
        directory.writeText("a file where the directory was")
    }

    private fun reviewAReport() {
        ai.reply = { FakeAi.replyJson(listOf("2160-0", "Kreatinin", "0,8", "mg/dl")) }
        viewModel.importText("Kreatinin 0,8 mg/dl")
        await(viewModel.importState) { it is ImportState.Reviewing }
    }

    @Test
    fun aSecondTapOnSaveWhileTheFirstIsSavingSavesOnce() {
        reviewAReport()
        viewModel.saveDraft()
        assertTrue("Save is off from the first tap", viewModel.isSaving.value)
        viewModel.saveDraft()
        await(viewModel.message) { it != null }
        await(viewModel.isSaving) { !it }
        idle()
        assertEquals(1, runBlocking { LocalReportStore(app, "test").loadReports() }.size)
        assertEquals(1, viewModel.reports.value.size)
    }

    /** A save that ends after a newer review opened (a shared report, a new entry) leaves that review alone. */
    @Test
    fun aSaveThatEndsLateNeverClosesANewerReview() {
        reviewAReport()
        viewModel.saveDraft()
        assertTrue("Still saving", viewModel.isSaving.value)
        viewModel.startManualEntry()
        await(viewModel.isSaving) { !it }
        idle()
        assertEquals(1, viewModel.reports.value.size)
        val draft = (viewModel.importState.value as? ImportState.Reviewing)?.draft
        assertTrue("The new entry is still open: $draft", draft != null && draft.engine == null && draft.values.size == 1)
    }

    /** Import Reports picked again while the file is still importing: its reports are saved once. */
    @Test
    fun twoOverlappingImportsOfOneFileSaveItsReportsOnce() {
        val transfer = file("report.xml", cda)
        viewModel.importReports(transfer)
        viewModel.importReports(transfer)
        await(viewModel.message) { it == app.getString(R.string.transfer_summary, 0, 1) || viewModel.reports.value.size > 1 }
        idle()
        assertEquals(1, runBlocking { LocalReportStore(app, "test").loadReports() }.size)
    }

    @Test
    fun aFailedSaveSaysSoInTheUsersWordsAndKeepsTheReview() {
        reviewAReport()
        breakTheReportStore()
        viewModel.saveDraft()
        // Exactly the localized sentence: no exception text, whichever way it failed.
        assertEquals(app.getString(R.string.error_save_storage), await(viewModel.message) { it != null })
        assertTrue(viewModel.importState.value is ImportState.Reviewing)
        assertTrue(viewModel.reports.value.isEmpty())
    }

    @Test
    fun aTransferFileThatCantBeSavedIsReportedNotCountedAsImported() {
        breakTheReportStore()
        viewModel.importReports(file("report.xml", cda))
        assertEquals(app.getString(R.string.transfer_summary_failed, 0, 0, 1), await(viewModel.message) { it != null })
    }

    @Test
    fun aTransferFileIsntImportedWhileTheSavedReportsCantBeRead() {
        // Health Connect is chosen but out of reach (there's none under Robolectric): with its reports
        // missing from the list, nothing could be skipped and everything would come in as a duplicate.
        app.container.preferences.setStorageMode(StorageMode.HEALTH_CONNECT)

        viewModel.importReports(file("report.xml", cda))

        assertEquals(app.getString(R.string.transfer_reports_unreadable), await(viewModel.message) { it != null })
        assertTrue(runBlocking { LocalReportStore(app, "test").loadReports() }.isEmpty())
    }

    @Test
    fun aReadOnlyReportNeverOpensForEditing() {
        val report = LabReport(
            "report", LocalDate(2026, 3, 1), "", "",
            listOf(LabReport.Entry("entry", "4548-4", "HbA1c", "6.5", 6.5, "%")),
        )
        viewModel.editReport(report.copy(unreadValues = 1))
        idle()
        assertEquals(ImportState.Idle, viewModel.importState.value)

        viewModel.editReport(report)
        assertEquals(report, (await(viewModel.importState) { it is ImportState.Reviewing } as ImportState.Reviewing).draft.original)
    }

    @Test
    fun importedReportsGetFreshEntryIds() {
        // Health Connect upserts by entry id: a file's older version must not overwrite the report here.
        val source = LabReport(
            "report", LocalDate(2026, 3, 1), "", "",
            listOf(LabReport.Entry("entry", "4548-4", "HbA1c", "6.5", 6.5, "%")),
        )
        viewModel.importReports(file("transfer.json", ReportTransfer.export(listOf(source), "test").contents))
        val imported = await(viewModel.reports) { it.isNotEmpty() }.single()
        assertEquals(source.entries.single().numericValue, imported.entries.single().numericValue)
        assertFalse(imported.id == source.id)
        assertFalse(imported.entries.single().id == source.entries.single().id)
    }

    @Test
    fun reportsWaitForTheCatalogBeforeLoading() {
        await(viewModel.isLoading) { !it }
        val saved = LabReport.fromValues(
            listOf(LabValue(code = "4548-4", name = "HbA1c", displayValue = "6,5", numericValue = 6.5, unit = "%")),
            LocalDate(2026, 3, 1), "", "",
        )
        runBlocking { LocalReportStore(app, "test").save(saved) }
        LabImporterApplication.catalogReady.value = false
        try {
            // Until the catalog is open, nothing may read, move or re-encode reports.
            viewModel.refresh()
            repeat(30) {
                idle()
                Thread.sleep(10)
            }
            assertTrue(viewModel.reports.value.isEmpty())
            LabImporterApplication.catalogReady.value = true
            assertEquals(saved.id, await(viewModel.reports) { it.isNotEmpty() }.single().id)
        } finally {
            LabImporterApplication.catalogReady.value = true
        }
    }

    private val exports: File get() = File(app.cacheDir, "exports")

    @Test
    fun oldExportsAreRemovedOnLaunchButNotOneStillBeingSent() {
        val directory = exports.apply { mkdirs() }
        val stale = File(directory, "LabResults-2020-01-01.xml").apply { writeText("<health record/>") }
        stale.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        // This process may have been killed while the mail app the file went to stayed open.
        val recent = File(directory, "LabResults-2020-01-02.xml").apply { writeText("<health record/>") }
        AppViewModel(app)
        val deadline = System.currentTimeMillis() + 30_000
        while (stale.exists() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        assertFalse(stale.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun onlyTheLatestExportStaysOnDisk() {
        reviewAReport()
        viewModel.saveDraft()
        await(viewModel.reports) { it.size == 1 }
        val report = viewModel.reports.value.single()
        viewModel.exportCda(report)
        viewModel.exportCda(report.copy(date = LocalDate(2020, 1, 1)))
        assertEquals(listOf("LabResults-2020-01-01.xml"), exports.listFiles().orEmpty().map { it.name })
        viewModel.exportAllReports()
        assertEquals(1, exports.listFiles().orEmpty().size)
        assertEquals("json", exports.listFiles().orEmpty().single().extension)
    }

    @Test
    fun anExportOutlivesTheUsersReturnToTheApp() {
        reviewAReport()
        viewModel.saveDraft()
        await(viewModel.reports) { it.size == 1 }
        viewModel.exportCda(viewModel.reports.value.single())
        val shared = exports.listFiles().orEmpty().single()

        // The user sends the file to a mail app, and checks a value here before sending the mail.
        runBlocking { viewModel.removeExpiredExports().join() }
        assertTrue("A target reading the file only when it sends would find nothing", shared.exists())

        // Coming back the next day, whatever had to send it has.
        shared.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        runBlocking { viewModel.removeExpiredExports().join() }
        assertFalse(shared.exists())
    }

    @Test
    fun aDamagedTransferFileSaysItCouldntCarryEverything() {
        val source = FhirLabCodec.encodeForSaving(
            LabReport(
                "report", LocalDate(2026, 3, 1), "", "",
                listOf(
                    LabReport.Entry("a", "4548-4", "HbA1c", "6.5", 6.5, "%"),
                    LabReport.Entry("b", "2160-0", "Creatinine", "0.8", 0.8, "mg/dl"),
                ),
            ),
            "test",
        ).map { it.json }
        val damaged = listOf(source.first().replace("effectiveDateTime", "lost"), source.last())
        val bundle = "{\"resourceType\":\"Bundle\",\"entry\":[" + damaged.joinToString(",") { "{\"resource\":$it}" } + "]}"

        viewModel.importReports(file("damaged.json", bundle))

        val summary = await(viewModel.message) { it != null }
        assertEquals(app.getString(R.string.transfer_summary, 1, 0) + " " + app.getString(R.string.transfer_damaged_some, 1), summary)
        assertEquals(1, viewModel.reports.value.single().entries.size)
    }

    @Test
    fun aSharedDocumentSpeaksTheAppsLanguage() {
        reviewAReport()
        viewModel.saveDraft()
        await(viewModel.reports) { it.size == 1 }
        viewModel.exportCda(viewModel.reports.value.single())
        val xml = exports.listFiles().orEmpty().single().readText()
        assertTrue(xml.contains("<languageCode code=\"en\"/>"))
        assertTrue(xml.contains("<th>Test</th><th>Result</th>"))
    }

    @Test
    fun aFileFromANewerVersionAsksForAnUpdate() {
        viewModel.importReports(file("newer.xml", cda.replace("LabImporter CDA v${ReportSchema.VERSION}", "LabImporter CDA v${ReportSchema.VERSION + 1}")))
        assertEquals(app.getString(R.string.transfer_newer_version), await(viewModel.message) { it != null })
        assertTrue(viewModel.reports.value.isEmpty())
    }

    /** A stored report with a code no build can write: older files can hold one, and only a save checks. */
    private fun storeAReportWithAnUnknownCode(vararg entries: LabReport.Entry) {
        val resources = FhirLabCodec.encode(LabReport("a", LocalDate(2026, 3, 1), "", "", entries.toList()), "test").map { it.json.replace("2823-3", "NOT-A-CODE") }
        val json = Json.encodeToString(ListSerializer(String.serializer()), resources)
        File(app.noBackupFilesDir, "reports").apply { mkdirs() }.resolve("a.json").writeText(json)
        viewModel.refresh()
        await(viewModel.reports) { it.size == 1 }
    }

    @Test
    fun anExportThatWouldLeaveValuesOutAsksFirst() {
        storeAReportWithAnUnknownCode(
            LabReport.Entry("a1", "2160-0", "Creatinine", "0.8", 0.8, "mg/dl"),
            LabReport.Entry("a2", "2823-3", "Potassium", "4.1", 4.1, "mmol/l"),
        )

        assertEquals(ReportsExport.LeavesOut(1), viewModel.exportAllReports())
        assertTrue("Nothing is written until the user decides", exports.listFiles().orEmpty().isEmpty())

        assertTrue(viewModel.exportAllReports(allowLeavingOut = true) is ReportsExport.Ready)
        val file = exports.listFiles().orEmpty().single().readText()
        assertTrue("2160-0" in file)
        assertFalse("NOT-A-CODE" in file)
    }

    @Test
    fun anExportWithNothingToWriteFailsOutLoud() {
        storeAReportWithAnUnknownCode(LabReport.Entry("a1", "2823-3", "Potassium", "4.1", 4.1, "mmol/l"))

        assertEquals(ReportsExport.Failed, viewModel.exportAllReports(allowLeavingOut = true))

        assertEquals(app.getString(R.string.transfer_export_nothing), viewModel.message.value)
        assertTrue(exports.listFiles().orEmpty().isEmpty())
    }
}
