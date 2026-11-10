package dev.idoodler.labimporter.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.core.LabReportStore
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.ui.HealthConnectState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reports in memory; saves and deletes fail on request, like a store that went away mid-way. */
open class MemoryStore : LabReportStore {
    val reports = linkedMapOf<String, LabReport>()
    var failingSaves = mutableSetOf<String>()
    var failingDeletes = mutableSetOf<String>()

    override suspend fun loadReports() = reports.values.toList()

    override suspend fun save(report: LabReport) {
        if (report.id in failingSaves) error("save failed")
        reports[report.id] = report // An upsert, as Health Connect does.
    }

    override suspend fun delete(report: LabReport) {
        if (report.id in failingDeletes) error("delete failed")
        reports.remove(report.id)
    }

    override suspend fun removeDropped(old: LabReport, updated: LabReport) = Unit // A save replaced the whole report.
}

class FakeHealthConnect : MemoryStore(), HealthConnectStore {
    var available = HealthConnectAvailability.AVAILABLE
    var granted = true
    var loadError: Exception? = null

    /** Runs before each save is stored: a test can hold one there. */
    var beforeSave: suspend (LabReport) -> Unit = {}

    override suspend fun save(report: LabReport) {
        beforeSave(report)
        super.save(report)
    }

    override suspend fun loadReports(): List<LabReport> {
        loadError?.let { throw it }
        return super.loadReports()
    }

    override fun availability() = available

    override suspend fun hasPermissions() = granted
}

@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class ReportRepositoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val local = MemoryStore()
    private val healthConnect = FakeHealthConnect()
    private lateinit var preferences: AppPreferences

    private fun report(id: String, day: Int) = LabReport(
        id, LocalDate(2026, 9, day), "", "",
        listOf(LabReport.Entry("$id-1", "2160-0", "Creatinine", "0.8", 0.8, "mg/dl")),
    )

    private val reports = listOf(report("a", 1), report("b", 2), report("c", 3))

    @Before
    fun start() {
        context.getSharedPreferences("labimporter", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = AppPreferences(context)
        reports.forEach { local.reports[it.id] = it }
    }

    private fun repository() = ReportRepository(local, healthConnect, preferences)

    @Test
    fun connectingMovesEveryReportIntoHealthConnect() = runTest {
        val repository = repository()
        assertEquals(3, repository.enableHealthConnect())
        assertTrue(local.reports.isEmpty())
        assertEquals(setOf("a", "b", "c"), healthConnect.reports.keys)
        assertEquals(listOf("c", "b", "a"), repository.reports.value.map { it.id })
    }

    @Test
    fun anInterruptedMoveIsFinishedOnTheNextLoadAndNoReportGoesMissing() = runTest {
        val repository = repository()
        healthConnect.failingSaves += "b" // Health Connect stops answering mid-way.
        assertEquals(2, repository.enableHealthConnect())
        assertEquals(setOf("b"), local.reports.keys) // Only what Health Connect has is deleted.
        assertEquals(listOf("c", "b", "a"), repository.reports.value.map { it.id }) // "b" is still listed.

        healthConnect.failingSaves.clear()
        repository.reload()
        assertTrue(local.reports.isEmpty())
        assertEquals(setOf("a", "b", "c"), healthConnect.reports.keys)
        assertEquals(3, repository.reports.value.size)
    }

    /**
     * A reload on resume moves a waiting report into Health Connect while the user saves an edit of it.
     * Side by side, the move wrote the old version over the edit and deleted the edited local copy.
     */
    @Test
    fun anEditSavedWhileAReloadMovesTheReportSurvives() = runTest {
        local.reports.clear()
        val old = report("a", 1)
        local.reports[old.id] = old // Waiting to move into Health Connect.
        preferences.setStorageMode(StorageMode.HEALTH_CONNECT)
        val repository = repository()
        val moving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        healthConnect.beforeSave = { saved ->
            if (saved == old && !moving.isCompleted) {
                moving.complete(Unit)
                release.await() // The move holds here, with the old version in hand.
            }
        }

        val reload = launch { repository.reload() }
        moving.await()
        val edited = old.copy(entries = listOf(old.entries.single().copy(displayValue = "1.4", numericValue = 1.4)))
        val replace = async { repository.replace(old, edited) }
        yield() // The edit goes as far as it can while the move waits.
        release.complete(Unit)
        reload.join()
        replace.await()

        assertEquals(edited, healthConnect.reports["a"])
        assertTrue(local.reports.isEmpty())
        assertEquals(listOf(edited), repository.reports.value)
    }

    @Test
    fun aReportInBothStoresIsListedOnceAndCleanedUpLater() = runTest {
        val repository = repository()
        local.failingDeletes += "a" // Saved to Health Connect, but the local copy stayed.
        repository.enableHealthConnect()
        assertEquals(setOf("a"), local.reports.keys)
        assertEquals(3, repository.reports.value.size)

        local.failingDeletes.clear()
        repository.reload()
        assertTrue(local.reports.isEmpty())
        assertEquals(3, repository.reports.value.size)
    }

    @Test
    fun reportsSavedWhileHealthConnectWasAwayMoveInWhenItIsBack() = runTest {
        val repository = repository()
        repository.enableHealthConnect()
        healthConnect.granted = false // Permission withdrawn: saves go to the app.
        repository.save(report("d", 4))
        assertEquals(setOf("d"), local.reports.keys)

        healthConnect.granted = true
        repository.reload()
        assertTrue(local.reports.isEmpty())
        assertEquals(4, repository.reports.value.size)
    }

    @Test
    fun healthConnectOutOfReachIsAFailedLoadNotAnEmptyList() = runTest {
        val repository = repository()
        repository.enableHealthConnect()
        assertFalse(repository.loadFailed.value)

        // Whichever way it went away, its reports are missing from what's listed now.
        healthConnect.granted = false
        repository.reload()
        assertTrue(repository.reports.value.isEmpty())
        assertTrue(repository.loadFailed.value)

        healthConnect.granted = true
        healthConnect.available = HealthConnectAvailability.UPDATE_REQUIRED
        repository.reload()
        assertTrue(repository.loadFailed.value)

        healthConnect.available = HealthConnectAvailability.AVAILABLE
        repository.reload()
        assertFalse(repository.loadFailed.value)
        assertEquals(3, repository.reports.value.size)
    }

    @Test
    fun aFailedLoadSaysWhyAndChoosingAppStorageEndsIt() = runTest {
        val repository = repository()
        repository.enableHealthConnect()
        assertEquals(null, repository.loadProblem.value)

        healthConnect.granted = false
        repository.reload()
        assertEquals(ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF, repository.loadProblem.value)

        healthConnect.granted = true
        healthConnect.available = HealthConnectAvailability.UPDATE_REQUIRED
        repository.reload()
        assertEquals(ReportsLoadProblem.HEALTH_CONNECT_UPDATE_REQUIRED, repository.loadProblem.value)

        for (gone in listOf(HealthConnectAvailability.NOT_INSTALLED, HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED)) {
            healthConnect.available = gone
            repository.reload()
            assertEquals(ReportsLoadProblem.HEALTH_CONNECT_UNAVAILABLE, repository.loadProblem.value)
        }

        // The way out when it never comes back: new saves go to the app, and nothing is deleted.
        val inHealthConnect = healthConnect.reports.keys.toSet()
        assertTrue(repository.wantsHealthConnect)
        repository.useLocalStorage()
        assertFalse(repository.wantsHealthConnect)
        assertEquals(null, repository.loadProblem.value)
        assertEquals(inHealthConnect, healthConnect.reports.keys.toSet())
    }

    @Test
    fun aReadThatFailsIsNotBlamedOnAccess() = runTest {
        val repository = repository()
        repository.enableHealthConnect()

        healthConnect.loadError = java.io.IOException("read failed")
        repository.reload()
        assertEquals(ReportsLoadProblem.READ_FAILED, repository.loadProblem.value)

        // Access can go between the check and the read.
        healthConnect.loadError = SecurityException("no permission")
        repository.reload()
        assertEquals(ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF, repository.loadProblem.value)
    }

    @Test
    fun anOfferToMoveReportsIsNotMadeToSomeoneWhoAlreadyChoseHealthConnect() {
        val cutOff = HealthConnectState(HealthConnectAvailability.AVAILABLE, granted = false, inUse = false, chosen = true)
        assertFalse(cutOff.offersMove(hasReports = true, dismissed = false))
    }

    @Test
    fun choosingAppStorageIsNotAFailedLoad() = runTest {
        val repository = repository()
        repository.enableHealthConnect()
        healthConnect.granted = false

        repository.useLocalStorage()

        assertFalse(repository.loadFailed.value)
    }

    @Test
    fun withoutHealthConnectNothingMoves() = runTest {
        healthConnect.available = HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED // Android 15.
        val repository = repository()
        assertEquals(0, repository.enableHealthConnect())
        assertEquals(StorageMode.LOCAL, preferences.storageMode.value)
        assertEquals(3, local.reports.size)
        repository.reload()
        assertEquals(3, repository.reports.value.size)
    }

    @Test
    fun theMoveIsOfferedOnceHealthConnectIsAvailableAndUnconnected() {
        // After the update to Android 16: available, never connected, reports in the app.
        val updated = HealthConnectState(HealthConnectAvailability.AVAILABLE, granted = false, inUse = false)
        assertTrue(updated.offersMove(hasReports = true, dismissed = false))
        assertFalse(updated.offersMove(hasReports = true, dismissed = true)) // "Not Now".
        assertFalse(updated.offersMove(hasReports = false, dismissed = false)) // Nothing to move.
        // Still Android 15, already connected, or connected and then switched to app storage on purpose.
        assertFalse(HealthConnectState(HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED).offersMove(true, false))
        assertFalse(HealthConnectState(HealthConnectAvailability.AVAILABLE, granted = true, inUse = true).offersMove(true, false))
        assertFalse(HealthConnectState(HealthConnectAvailability.AVAILABLE, granted = true, inUse = false).offersMove(true, false))
    }

    @Test
    fun turningTheOfferDownIsRemembered() {
        preferences.setHealthConnectOfferDismissed(true)
        assertTrue(AppPreferences(context).healthConnectOfferDismissed.value)
    }
}
