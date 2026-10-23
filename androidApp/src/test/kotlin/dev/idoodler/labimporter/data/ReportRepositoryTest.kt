package dev.idoodler.labimporter.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.core.LabReportStore
import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.ui.HealthConnectState
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
}

class FakeHealthConnect : MemoryStore(), HealthConnectStore {
    var available = HealthConnectAvailability.AVAILABLE
    var granted = true

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
