package dev.idoodler.labimporter.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.droppedEntryIds
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.export.ReportSchema
import dev.idoodler.labimporter.core.export.ReportEncodingException
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.platform.NumberFormatting
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.After
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Health Connect as it behaves for this app, over the real codec: one record per
 * entry, upserted by entry id, so a report is only what its records add up to.
 */
private class FhirHealthConnect : HealthConnectStore {
    val records = linkedMapOf<String, String>()
    val savedReports = mutableListOf<String>()
    val deletedRecords = mutableListOf<String>()
    var failSave: (LabReport) -> Boolean = { false }
    var failLoads = false
    var failDeletes = false

    /** Accepts every save, and keeps none of them. */
    var losesSaves = false

    override fun availability() = HealthConnectAvailability.AVAILABLE

    override suspend fun hasPermissions() = true

    override var unreadableReports = 0

    override var newerVersionReports = 0

    override suspend fun loadReports(): List<LabReport> {
        if (failLoads) throw IOException("read failed")
        val stored = FhirLabCodec.decodeStored(records.values.toList())
        unreadableReports = stored.unreadableReports
        newerVersionReports = stored.newerVersionReports
        return stored.reports
    }

    override suspend fun save(report: LabReport) {
        if (failSave(report)) throw IOException("save failed")
        val resources = FhirLabCodec.encodeForSaving(report, "test")
        savedReports += report.id
        if (!losesSaves) resources.forEach { records[it.id] = it.json }
    }

    override suspend fun delete(report: LabReport) = remove(report.entries.map { it.id })

    override suspend fun removeDropped(old: LabReport, updated: LabReport) = remove(droppedEntryIds(old, updated))

    private fun remove(ids: List<String>) {
        if (failDeletes) throw IOException("delete failed")
        ids.forEach { records.remove(it) }
        deletedRecords += ids
    }
}

/** Every way a save can go wrong, against the real codec, the real LOINC catalog and the real file store. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class ReportSavingFaultTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val directory: File get() = File(context.noBackupFilesDir, "reports")
    private val healthConnect = FhirHealthConnect()
    private lateinit var preferences: AppPreferences
    private lateinit var local: LocalReportStore

    private fun entry(id: String, code: String, value: Double) = LabReport.Entry(id, code, code, NumberFormatting.exactValue(value), value, "mg/dl")

    private fun report(id: String, day: Int = 1, vararg entries: LabReport.Entry = arrayOf(entry("$id-1", "2160-0", 0.8))) =
        LabReport(id, LocalDate(2026, 9, day), "", "", entries.toList())

    private val threeReports = listOf(report("a", 1), report("b", 2), report("c", 3))

    private fun repository(localStore: LocalReportStore = local) = ReportRepository(localStore, healthConnect, preferences)

    private fun failRename(from: File, to: File) {
        throw IOException("rename $from to $to failed")
    }

    private fun useHealthConnect() = preferences.setStorageMode(StorageMode.HEALTH_CONNECT)

    private fun files() = directory.listFiles().orEmpty().map { it.name }.toSet()

    @Before
    fun start() {
        context.getSharedPreferences("labimporter", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = AppPreferences(context)
        directory.deleteRecursively()
        local = LocalReportStore(context, "test")
    }

    @After
    fun reopenCatalog() {
        LabCore.start(checkNotNull(System.getProperty("loinc.db")), "en-US") { LabDisplayPreferences() }
    }

    // region Replacing a report

    @Test
    fun replaceWithAFailingSaveKeepsTheOldReportInHealthConnect() = runTest {
        useHealthConnect()
        val repository = repository()
        val old = report("a", 1, entry("e1", "2160-0", 0.8), entry("e2", "2823-3", 4.1))
        repository.save(old)
        healthConnect.failSave = { true }

        assertThrows(IOException::class.java) { runBlocking { repository.replace(old, report("a", 1, entry("e2", "2823-3", 5.0))) } }

        assertEquals(setOf("e1", "e2"), healthConnect.records.keys)
        assertEquals(emptyList<String>(), healthConnect.deletedRecords)
        repository.reload()
        assertEquals(old, repository.reports.value.single())
    }

    @Test
    fun replaceWithAFailingSaveKeepsTheOldReportInTheApp() = runTest {
        val old = report("a", 1, entry("e1", "2160-0", 0.8))
        local.save(old)
        val repository = repository(LocalReportStore(context, "test", moveFile = ::failRename))

        assertThrows(IOException::class.java) { runBlocking { repository.replace(old, report("a", 1, entry("e1", "2160-0", 9.9))) } }

        assertEquals(old, local.loadReports().single())
    }

    @Test
    fun replaceRemovesOnlyTheEntriesTheNewVersionDropped() = runTest {
        useHealthConnect()
        val repository = repository()
        val old = report("a", 1, entry("dropped", "2160-0", 0.8), entry("kept", "2823-3", 4.1))
        repository.save(old)
        val updated = report("a", 1, entry("kept", "2823-3", 5.0), entry("added", "4548-4", 6.5))

        assertEquals(ReplaceOutcome.REPLACED, repository.replace(old, updated))

        assertEquals(listOf("dropped"), healthConnect.deletedRecords)
        assertEquals(updated.entries.toSet(), repository.reports.value.single().entries.toSet())
    }

    @Test
    fun replaceInTheAppKeepsTheNewVersionUnderTheSameFile() = runTest {
        val repository = repository()
        val old = report("a", 1, entry("dropped", "2160-0", 0.8), entry("kept", "2823-3", 4.1))
        repository.save(old)
        val updated = report("a", 1, entry("kept", "2823-3", 5.0))

        assertEquals(ReplaceOutcome.REPLACED, repository.replace(old, updated))

        assertEquals(updated, repository.reports.value.single())
        assertEquals(setOf("a.json"), files())
    }

    @Test
    fun replaceWhereLeftoversCantBeRemovedKeepsTheNewReportAndWarns() = runTest {
        useHealthConnect()
        val repository = repository()
        val old = report("a", 1, entry("dropped", "2160-0", 0.8), entry("kept", "2823-3", 4.1))
        repository.save(old)
        healthConnect.failDeletes = true

        val outcome = repository.replace(old, report("a", 1, entry("kept", "2823-3", 5.0)))

        assertEquals(ReplaceOutcome.SAVED_WITH_LEFTOVERS, outcome)
        assertEquals(5.0, repository.reports.value.single().entries.single { it.id == "kept" }.numericValue)
    }

    @Test
    fun editingAReportStillWaitingToMoveIsntUndoneByTheMove() = runTest {
        useHealthConnect()
        val repository = repository()
        val old = report("a", 1, entry("e1", "2160-0", 0.8))
        local.save(old)
        healthConnect.failSave = { true }
        repository.reload() // Health Connect refuses: the report stays in the app, and is listed.
        assertEquals(old, repository.reports.value.single())
        healthConnect.failSave = { false }

        repository.replace(old, report("a", 1, entry("e1", "2160-0", 9.9)))
        repository.reload()

        assertEquals(9.9, repository.reports.value.single().entries.single().numericValue)
        assertTrue(local.loadReports().isEmpty())
    }

    @Test
    fun anEditThatCantReachTheWaitingCopyChangesNothing() = runTest {
        useHealthConnect()
        val old = report("a", 1, entry("e1", "2160-0", 0.8))
        healthConnect.save(old) // Moved, but the local copy stayed.
        local.save(old)
        val repository = repository(LocalReportStore(context, "test", moveFile = ::failRename))

        assertThrows(IOException::class.java) { runBlocking { repository.replace(old, report("a", 1, entry("e1", "2160-0", 9.9))) } }

        assertEquals(old, healthConnect.loadReports().single())
        assertEquals(old, local.loadReports().single())
    }

    @Test
    fun editingAWaitingReportLeavesTheReportSharingItsEntryAlone() = runTest {
        val (edited, older) = reportsSharingAnEntry()
        val repository = repository()
        repository.reload()

        repository.replace(older, report("older", 1, entry("same-id", "2160-0", 9.9)))

        assertEquals(listOf(edited), healthConnect.loadReports())
        assertEquals(9.9, repository.reports.value.single { it.id == "older" }.entries.single().numericValue)
        assertEquals(1.5, repository.reports.value.single { it.id == "edited" }.entries.single().numericValue)
    }

    @Test
    fun anEditOfAReportInBothStoresIsListedAndRemovesWhatItDroppedEverywhere() = runTest {
        useHealthConnect()
        val old = report("a", 1, entry("dropped", "2160-0", 0.8), entry("kept", "2823-3", 4.1))
        healthConnect.save(old)
        local.save(old)
        healthConnect.failSave = { true } // So the edit waits, and Health Connect still has the old version.
        val repository = repository()

        val outcome = repository.replace(old, report("a", 1, entry("kept", "2823-3", 5.0)))

        assertEquals(ReplaceOutcome.REPLACED, outcome)
        assertEquals(5.0, repository.reports.value.single().entries.single().numericValue)
        assertEquals(setOf("kept"), healthConnect.records.keys)

        healthConnect.failSave = { false }
        repository.reload()
        assertEquals(5.0, healthConnect.loadReports().single().entries.single().numericValue)
        assertTrue(local.loadReports().isEmpty())
    }

    // endregion

    // region Deleting a report

    /** One saved in Health Connect, one waiting in the app, both using one entry id. */
    private suspend fun reportsSharingAnEntry(): Pair<LabReport, LabReport> {
        useHealthConnect()
        val edited = report("edited", 2, entry("same-id", "2160-0", 1.5))
        val older = report("older", 1, entry("same-id", "2160-0", 0.8))
        healthConnect.save(edited)
        local.save(older)
        return edited to older
    }

    @Test
    fun deletingAWaitingReportLeavesTheReportSharingItsEntryAlone() = runTest {
        val (edited, older) = reportsSharingAnEntry()
        val repository = repository()
        repository.reload()
        assertEquals(setOf("edited", "older"), repository.reports.value.map { it.id }.toSet())

        repository.delete(older)

        assertEquals(listOf(edited), repository.reports.value)
        assertEquals(listOf(edited), healthConnect.loadReports())
        assertTrue(local.loadReports().isEmpty())
    }

    @Test
    fun aDeletedWaitingReportDoesntMoveBack() = runTest {
        useHealthConnect()
        val waiting = report("a")
        local.save(waiting)
        healthConnect.failSave = { true }
        val repository = repository()
        repository.reload()
        assertEquals(listOf(waiting), repository.reports.value)
        healthConnect.failSave = { false }
        healthConnect.failDeletes = true // There's nothing of it in Health Connect to refuse deleting.

        repository.delete(waiting)
        repository.reload()

        assertTrue(repository.reports.value.isEmpty())
        assertTrue(healthConnect.records.isEmpty())
        assertTrue(local.loadReports().isEmpty())
    }

    @Test
    fun aReportInBothStoresIsDeletedFromBoth() = runTest {
        useHealthConnect()
        val both = report("a")
        healthConnect.save(both)
        local.save(both)
        val repository = repository()

        assertEquals(0, repository.deleteAll(listOf(both)))
        repository.reload()

        assertTrue(repository.reports.value.isEmpty())
        assertTrue(healthConnect.records.isEmpty())
        assertTrue(local.loadReports().isEmpty())
    }

    @Test
    fun aDeleteHealthConnectRefusesIsAFailureAndKeepsTheReport() = runTest {
        useHealthConnect()
        val saved = report("a")
        healthConnect.save(saved)
        healthConnect.failDeletes = true
        val repository = repository()

        assertThrows(IOException::class.java) { runBlocking { repository.delete(saved) } }
        assertEquals(1, repository.deleteAll(listOf(saved)))

        assertEquals(listOf(saved), repository.reports.value)
    }

    @Test
    fun aDeleteThatCantReadHealthConnectIsAFailureAndTouchesNothing() = runTest {
        useHealthConnect()
        val waiting = report("a")
        local.save(waiting)
        healthConnect.failLoads = true

        assertEquals(1, repository().deleteAll(listOf(waiting)))

        assertEquals(listOf(waiting), local.loadReports())
    }

    @Test
    fun aFileThatWontGoIsAFailedDeleteNotADeletedReport() = runTest {
        File(directory, "a.json/occupied").apply { parentFile!!.mkdirs() }.writeText("x") // Nothing can delete a full directory.

        assertThrows(IOException::class.java) { runBlocking { local.delete(report("a")) } }
        assertThrows(IOException::class.java) { runBlocking { repository().delete(report("a")) } }

        assertTrue(File(directory, "a.json").exists())
    }

    @Test
    fun deletingAFileThatsAlreadyGoneIsFine() = runTest {
        local.delete(report("never-saved"))
    }

    // endregion

    // region Moving into Health Connect

    @Test
    fun aMoveWithTheCatalogClosedKeepsTheLocalCopy() = runTest {
        threeReports.forEach { local.save(it) }
        LabCore.start("/nonexistent/loinc.db", "en-US") { LabDisplayPreferences() } // Being copied after an app update.
        val repository = repository()

        assertEquals(0, repository.enableHealthConnect())

        assertEquals(3, local.loadReports().size)
        assertTrue(healthConnect.records.isEmpty())
        assertEquals(3, repository.reports.value.size)

        reopenCatalog()
        repository.reload()
        assertTrue(local.loadReports().isEmpty())
        assertEquals(3, healthConnect.records.size)
    }

    @Test
    fun aMoveHealthConnectAcceptsButDoesntReadBackKeepsTheLocalCopy() = runTest {
        threeReports.forEach { local.save(it) }
        healthConnect.losesSaves = true
        val repository = repository()

        assertEquals(0, repository.enableHealthConnect())

        assertEquals(setOf("a", "b", "c"), healthConnect.savedReports.toSet())
        assertEquals(threeReports.toSet(), local.loadReports().toSet())
        assertEquals(3, repository.reports.value.size)
    }

    @Test
    fun aMoveThatFailsOnTheSecondOfThreeKeepsTheLastTwoAndMovesTheFirst() = runTest {
        threeReports.forEach { local.save(it) }
        var saves = 0
        healthConnect.failSave = { ++saves > 1 } // Health Connect stops accepting after the first.
        val repository = repository()

        assertEquals(1, repository.enableHealthConnect())

        val first = healthConnect.savedReports.single()
        assertEquals(setOf("$first-1"), healthConnect.records.keys)
        assertEquals(setOf("a", "b", "c") - first, local.loadReports().map { it.id }.toSet())
        assertEquals(setOf("a", "b", "c"), repository.reports.value.map { it.id }.toSet())

        healthConnect.failSave = { false }
        repository.reload()
        assertTrue(local.loadReports().isEmpty())
        assertEquals(setOf("a", "b", "c"), repository.reports.value.map { it.id }.toSet())
    }

    @Test
    fun aHealthConnectThatStopsReadingKeepsEveryLocalCopy() = runTest {
        threeReports.forEach { local.save(it) }
        useHealthConnect()
        healthConnect.failLoads = true
        val repository = repository()

        repository.reload()

        assertEquals(3, local.loadReports().size)
        assertTrue(repository.loadFailed.value)
    }

    @Test
    fun aMoveNeverOverwritesAnotherReportsEntry() = runTest {
        // Two reports with one entry id: a file imported again after the report was edited, in an older version.
        val edited = report("edited", 2, entry("same-id", "2160-0", 1.5))
        val older = report("older", 1, entry("same-id", "2160-0", 0.8))
        local.save(edited)
        local.save(older)
        val repository = repository()

        repository.enableHealthConnect()
        repository.reload()
        repository.reload()

        assertEquals(setOf("edited", "older"), repository.reports.value.map { it.id }.toSet())
        assertEquals(1, local.loadReports().size)
        assertEquals(1, healthConnect.records.size)
    }

    // endregion

    // region The file store

    @Test
    fun aFailedRenameThrowsAndLeavesTheOldFile() = runTest {
        val old = report("a", 1, entry("e1", "2160-0", 0.8))
        local.save(old)
        val before = File(directory, "a.json").readText()
        val store = LocalReportStore(context, "test", moveFile = ::failRename)

        assertThrows(IOException::class.java) { runBlocking { store.save(report("a", 1, entry("e1", "2160-0", 9.9))) } }

        assertEquals(before, File(directory, "a.json").readText())
        assertEquals(setOf("a.json"), files()) // No temporary copy of a health record stays behind.
    }

    @Test
    fun aRenameTheFileSystemRefusesIsAFailureNotASuccess() = runTest {
        File(directory, "a.json/occupied").apply { parentFile!!.mkdirs() }.writeText("x") // Nothing can replace a full directory.

        assertThrows(Exception::class.java) { runBlocking { local.save(report("a")) } }

        assertFalse(File(directory, "a.json.tmp").exists())
    }

    @Test
    fun savingOverAReportReplacesItAtomically() = runTest {
        local.save(report("a", 1, entry("e1", "2160-0", 0.8)))
        local.save(report("a", 1, entry("e1", "2160-0", 9.9)))
        assertEquals(9.9, local.loadReports().single().entries.single().numericValue)
        assertEquals(setOf("a.json"), files())
    }

    @Test
    fun anIdFromAFileStaysInsideTheDirectory() = runTest {
        val root = context.noBackupFilesDir
        val before = root.walkTopDown().filter { it.isFile }.toSet()
        val hostile = listOf(report("../evil"), report("../missing/evil", 2), report("..", 3), report("/etc/evil", 4))

        hostile.forEach { local.save(it) }

        val written = root.walkTopDown().filter { it.isFile }.toSet() - before
        assertEquals(hostile.size, written.size)
        assertTrue(written.all { it.parentFile == directory })
        assertEquals(hostile.map { it.id }.toSet(), local.loadReports().map { it.id }.toSet())

        hostile.forEach { local.delete(it) }
        assertTrue(local.loadReports().isEmpty())
    }

    @Test
    fun idsThatCleanUpToTheSameNameDontOverwriteEachOther() = runTest {
        val reports = listOf(report("a/b"), report("ab", 2), report("a.b", 3))
        reports.forEach { local.save(it) }
        assertEquals(reports.map { it.id }.toSet(), local.loadReports().map { it.id }.toSet())
    }

    @Test
    fun aCorruptFileIsCountedAndKept() = runTest {
        local.save(report("good"))
        val garbage = "not json — and whatever it was, it's someone's health record"
        val truncated = File(directory, "truncated.json").apply { writeText("[\"{\\\"resourceType\\\":") }
        val corrupt = File(directory, "corrupt.json").apply { writeText(garbage) }
        val repository = repository()

        repository.reload()

        assertEquals(listOf("good"), repository.reports.value.map { it.id })
        assertEquals(2, repository.unreadableReports.value)
        assertEquals(garbage, corrupt.readText())
        assertTrue(truncated.exists())

        corrupt.delete()
        truncated.delete()
        repository.reload()
        assertEquals(0, repository.unreadableReports.value)
    }

    @Test
    fun aFileMissingSomeValuesIsCountedAndNotListedHalfWay() = runTest {
        val whole = report("a", 1, entry("e1", "2160-0", 0.8), entry("e2", "2823-3", 4.1))
        local.save(whole)
        val file = File(directory, "a.json")
        file.writeText(file.readText().replaceFirst("effectiveDateTime", "lostTheDate"))
        val repository = repository()

        repository.reload()

        assertTrue(repository.reports.value.isEmpty())
        assertEquals(1, repository.unreadableReports.value)
        assertTrue(file.exists())
    }

    /** The store's file: a JSON list of FHIR resources, each itself a JSON string. */
    private fun fileFromANewerVersion(file: String): String {
        val resources = Json.decodeFromString(ListSerializer(String.serializer()), file)
        return Json.encodeToString(ListSerializer(String.serializer()), resources.map(::fromANewerVersion))
    }

    private fun fromANewerVersion(json: String) = json.replace("\"code\":\"${ReportSchema.VERSION}\"", "\"code\":\"${ReportSchema.VERSION + 1}\"")

    @Test
    fun aFileFromANewerVersionIsCountedAndKept() = runTest {
        local.save(report("good"))
        local.save(report("later"))
        val later = File(directory, "later.json")
        later.writeText(fileFromANewerVersion(later.readText()))
        val written = later.readText()
        val repository = repository()

        repository.reload()

        assertEquals(listOf("good"), repository.reports.value.map { it.id })
        assertEquals(1, repository.newerVersionReports.value)
        assertEquals("It needs an update, not a repair", 0, repository.unreadableReports.value)
        assertEquals(written, later.readText())
    }

    @Test
    fun aMoveLeavesAFileFromANewerVersionWhereItIs() = runTest {
        local.save(report("later"))
        val later = File(directory, "later.json")
        later.writeText(fileFromANewerVersion(later.readText()))
        val repository = repository()

        assertEquals(0, repository.enableHealthConnect())

        assertTrue(later.exists())
        assertTrue(healthConnect.records.isEmpty())
    }

    @Test
    fun healthConnectReportsFromANewerVersionAreCountedAndKept() = runTest {
        useHealthConnect()
        val repository = repository()
        repository.save(report("good"))
        repository.save(report("later"))
        val laterId = report("later").entries.single().id
        healthConnect.records[laterId] = fromANewerVersion(healthConnect.records.getValue(laterId))

        repository.reload()

        assertEquals(listOf("good"), repository.reports.value.map { it.id })
        assertEquals(1, repository.newerVersionReports.value)
        assertTrue(laterId in healthConnect.records)

        // Its count only means something while Health Connect's reports are the ones listed.
        repository.useLocalStorage()
        assertEquals(0, repository.newerVersionReports.value)
    }

    @Test
    fun aDamagedHealthConnectRecordIsCounted() = runTest {
        useHealthConnect()
        val repository = repository()
        repository.save(report("hurt", 1, entry("e1", "2160-0", 0.8), entry("e2", "2823-3", 4.1)))
        healthConnect.records["e2"] = healthConnect.records.getValue("e2").replace("effectiveDateTime", "lostTheDate")

        repository.reload()

        assertEquals(1, repository.unreadableReports.value)
        assertEquals(listOf("e1"), repository.reports.value.single().entries.map { it.id })
    }

    @Test
    fun aMoveLeavesAnUnreadableFileWhereItIs() = runTest {
        local.save(report("good"))
        val corrupt = File(directory, "corrupt.json").apply { writeText("not json") }
        val repository = repository()

        assertEquals(1, repository.enableHealthConnect())

        assertTrue(corrupt.exists())
        assertEquals(1, repository.unreadableReports.value)
    }

    @Test
    fun aResultTheCatalogDoesntKnowFailsTheSaveInEveryStore() = runTest {
        val unknown = report("a", 1, entry("e1", "2160-0", 0.8), entry("e2", "NOPE", 1.0))

        assertThrows(ReportEncodingException::class.java) { runBlocking { local.save(unknown) } }
        assertThrows(ReportEncodingException::class.java) { runBlocking { healthConnect.save(unknown) } }

        assertEquals(emptySet<String>(), files())
    }

    // endregion

    // region Importing and loading

    @Test
    fun saveAllWithOneFailureSavesTheOthersAndCountsIt() = runTest {
        val repository = repository()
        // Not a fake failure: this is what the real store does with a result it can't encode.
        val unsavable = report("b", 2, entry("b-1", "NOPE", 1.0))

        assertEquals(1, repository.saveAll(listOf(report("a"), unsavable, report("c", 3))))

        assertEquals(setOf("a", "c"), repository.reports.value.map { it.id }.toSet())
    }

    @Test
    fun aFailedLoadIsNotNoReports() = runTest {
        useHealthConnect()
        val repository = repository()
        repository.save(report("a"))
        assertFalse(repository.loadFailed.value)

        healthConnect.failLoads = true
        repository.reload()
        assertTrue(repository.loadFailed.value)
        assertEquals(listOf("a"), repository.reports.value.map { it.id }) // What was on screen stays.
        assertFalse(repository.isLoading.value)

        healthConnect.failLoads = false
        repository.reload()
        assertFalse(repository.loadFailed.value)
    }

    @Test
    fun aFirstLoadThatFailsLeavesNoReportsButSaysSo() = runTest {
        useHealthConnect()
        healthConnect.failLoads = true
        val repository = repository()

        repository.reload()

        assertTrue(repository.reports.value.isEmpty())
        assertTrue(repository.loadFailed.value)
    }

    // endregion
}
