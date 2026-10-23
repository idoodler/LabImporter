package dev.idoodler.labimporter.data

import android.util.Log
import dev.idoodler.labimporter.core.LabReportStore
import dev.idoodler.labimporter.core.model.LabReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of saved reports for the UI. There is no app database of
 * record: reports are re-read from the active store (Health Connect or the
 * on-device fallback) on launch, on resume and after every change.
 */
class ReportRepository(
    private val local: LabReportStore,
    private val healthConnect: HealthConnectStore,
    private val preferences: AppPreferences,
) {
    private val _reports = MutableStateFlow<List<LabReport>>(emptyList())
    val reports: StateFlow<List<LabReport>> = _reports.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private suspend fun store(): LabReportStore {
        val wantsHealthConnect = preferences.storageMode.value == StorageMode.HEALTH_CONNECT
        return if (wantsHealthConnect &&
            healthConnect.availability() == HealthConnectAvailability.AVAILABLE &&
            healthConnect.hasPermissions()
        ) {
            healthConnect
        } else {
            local
        }
    }

    suspend fun isUsingHealthConnect(): Boolean = store() === healthConnect

    suspend fun reload() {
        _isLoading.value = true
        runCatching {
            val target = store()
            // Reports a move into Health Connect didn't get to — it stopped half-way, or Health
            // Connect was unavailable for a while and saves went to the app: move them now, and
            // list any still left, so no report seems lost while Health Connect is in use.
            val stillLocal = if (target === healthConnect) moveLocalReports() else emptyList()
            (target.loadReports() + stillLocal).distinctBy { it.id }.sortedByDescending { it.date }
        }
            .onSuccess { _reports.value = it; _lastError.value = null }
            .onFailure { _lastError.value = it.message }
        _isLoading.value = false
    }

    /**
     * Moves every report kept in the app into Health Connect, deleting each
     * local copy only once Health Connect has it (saving again is harmless:
     * Health Connect upserts by id). Returns the reports that couldn't be moved.
     */
    private suspend fun moveLocalReports(): List<LabReport> =
        local.loadReports().filterNot { report ->
            runCatching {
                healthConnect.save(report)
                local.delete(report)
            }.onFailure { Log.w(TAG, "Couldn't move report ${report.id} to Health Connect yet", it) }.isSuccess
        }

    suspend fun save(report: LabReport) {
        store().save(report)
        reload()
    }

    /** Saves several reports (an imported transfer file) and reloads once. */
    suspend fun saveAll(reports: List<LabReport>) {
        val target = store()
        reports.forEach { target.save(it) }
        reload()
    }

    /** Replaces [old] with [updated] (edit flow): same id, rewritten entries. */
    suspend fun replace(old: LabReport, updated: LabReport) {
        val target = store()
        target.delete(old)
        target.save(updated)
        reload()
    }

    suspend fun delete(report: LabReport) {
        store().delete(report)
        reload()
    }

    /** Deletes [reports] one by one, reloads once, and returns how many couldn't be removed. */
    suspend fun deleteAll(reports: List<LabReport>): Int {
        val target = store()
        val failed = reports.count { report -> runCatching { target.delete(report) }.isFailure }
        reload()
        return failed
    }

    /**
     * Turns Health Connect on and moves any on-device reports into it, so the
     * user never ends up with data split across two stores. Returns how many
     * moved; any that couldn't are moved on a later [reload] and listed meanwhile.
     */
    suspend fun enableHealthConnect(): Int {
        preferences.setStorageMode(StorageMode.HEALTH_CONNECT)
        if (!isUsingHealthConnect()) {
            preferences.setStorageMode(StorageMode.LOCAL)
            return 0
        }
        val pending = local.loadReports().size
        val left = moveLocalReports()
        reload()
        return pending - left.size
    }

    suspend fun useLocalStorage() {
        preferences.setStorageMode(StorageMode.LOCAL)
        reload()
    }

    private companion object {
        const val TAG = "ReportRepository"
    }
}
