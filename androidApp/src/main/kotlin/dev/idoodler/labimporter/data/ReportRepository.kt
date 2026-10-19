package dev.idoodler.labimporter.data

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
    private val local: LocalReportStore,
    private val healthConnect: HealthConnectReportStore,
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
        runCatching { store().loadReports().sortedByDescending { it.date } }
            .onSuccess { _reports.value = it; _lastError.value = null }
            .onFailure { _lastError.value = it.message }
        _isLoading.value = false
    }

    suspend fun save(report: LabReport) {
        store().save(report)
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

    /**
     * Turns Health Connect on and moves any on-device reports into it, so the
     * user never ends up with data split across two stores.
     */
    suspend fun enableHealthConnect(): Int {
        preferences.setStorageMode(StorageMode.HEALTH_CONNECT)
        if (!isUsingHealthConnect()) {
            preferences.setStorageMode(StorageMode.LOCAL)
            return 0
        }
        val pending = local.loadReports()
        pending.forEach { healthConnect.save(it) }
        local.deleteAll()
        reload()
        return pending.size
    }

    suspend fun useLocalStorage() {
        preferences.setStorageMode(StorageMode.LOCAL)
        reload()
    }
}
