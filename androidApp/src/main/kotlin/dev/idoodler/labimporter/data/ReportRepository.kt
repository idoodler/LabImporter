package dev.idoodler.labimporter.data

import android.util.Log
import dev.idoodler.labimporter.core.LabReportStore
import dev.idoodler.labimporter.core.model.LabReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/** How [ReportRepository.replace] went, given that the new version is saved either way. */
enum class ReplaceOutcome {
    REPLACED,

    /** Parts of the old version couldn't be removed, so they may still show. Worth telling the user. */
    SAVED_WITH_LEFTOVERS,
}

/** Why the reports on screen may not be all there are. Each has its own way out; only the first is helped by trying again. */
enum class ReportsLoadProblem {
    /** Reading the store failed. */
    READ_FAILED,

    /** Health Connect is chosen, but LabImporter no longer has access. */
    HEALTH_CONNECT_ACCESS_OFF,

    /** Health Connect is chosen, but has to be updated first. */
    HEALTH_CONNECT_UPDATE_REQUIRED,

    /** Health Connect is chosen, but this device doesn't offer it (any more). */
    HEALTH_CONNECT_UNAVAILABLE,
}

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

    private val _loadFailed = MutableStateFlow(false)

    /** The last [reload] couldn't read the store: [reports] may be out of date, not empty. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    private val _loadProblem = MutableStateFlow<ReportsLoadProblem?>(null)

    /** Why [loadFailed], so the user can be told what actually helps. */
    val loadProblem: StateFlow<ReportsLoadProblem?> = _loadProblem.asStateFlow()

    private val _unreadableReports = MutableStateFlow(0)

    /** Saved reports that exist but can't be read, so [reports] leaves them out. */
    val unreadableReports: StateFlow<Int> = _unreadableReports.asStateFlow()

    private val _newerVersionReports = MutableStateFlow(0)

    /** Saved reports only a newer version of the app can read, so [reports] leaves them out. */
    val newerVersionReports: StateFlow<Int> = _newerVersionReports.asStateFlow()

    /** Whether the user chose Health Connect, which isn't the same as it being in use right now. */
    val wantsHealthConnect: Boolean get() = preferences.storageMode.value == StorageMode.HEALTH_CONNECT

    private suspend fun store(): LabReportStore =
        if (wantsHealthConnect &&
            healthConnect.availability() == HealthConnectAvailability.AVAILABLE &&
            healthConnect.hasPermissions()
        ) {
            healthConnect
        } else {
            local
        }

    suspend fun isUsingHealthConnect(): Boolean = store() === healthConnect

    suspend fun reload() {
        _isLoading.value = true
        var read: LabReportStore? = null
        try {
            val target = store()
            read = target
            // Reports a move into Health Connect didn't get to — it stopped half-way, or Health
            // Connect was unavailable for a while and saves went to the app: move them now, and
            // list any still left, so no report seems lost while Health Connect is in use.
            val stillLocal = if (target === healthConnect) moveLocalReports() else emptyList()
            // A waiting copy comes first: it's the newest version, the one the next move writes over Health Connect's.
            _reports.value = (stillLocal + target.loadReports()).distinctBy { it.id }.sortedByDescending { it.date }
            // Health Connect is chosen but out of reach (permission withdrawn, update required, a failed
            // check): saves can go to the app meanwhile, but what it holds isn't on this list.
            report(if (wantsHealthConnect && target !== healthConnect) healthConnectProblem() else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load the reports", e)
            // Access can go between the check and the read.
            report(if (e is SecurityException) ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF else ReportsLoadProblem.READ_FAILED)
        } finally {
            // Health Connect's counts only mean something while its reports are the ones listed.
            val inHealthConnect = read === healthConnect
            _unreadableReports.value = local.unreadableReports + if (inHealthConnect) healthConnect.unreadableReports else 0
            _newerVersionReports.value = local.newerVersionReports + if (inHealthConnect) healthConnect.newerVersionReports else 0
            _isLoading.value = false
        }
    }

    private fun report(problem: ReportsLoadProblem?) {
        _loadProblem.value = problem
        _loadFailed.value = problem != null
    }

    private fun healthConnectProblem(): ReportsLoadProblem = when (healthConnect.availability()) {
        HealthConnectAvailability.AVAILABLE -> ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF
        HealthConnectAvailability.UPDATE_REQUIRED -> ReportsLoadProblem.HEALTH_CONNECT_UPDATE_REQUIRED
        HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED, HealthConnectAvailability.NOT_INSTALLED -> ReportsLoadProblem.HEALTH_CONNECT_UNAVAILABLE
    }

    /**
     * Moves every report kept in the app into Health Connect, deleting each
     * local copy only once a read-back shows Health Connect has all its results
     * (saving again is harmless: Health Connect upserts by id). Returns the
     * reports that couldn't be moved.
     */
    private suspend fun moveLocalReports(): List<LabReport> {
        val pending = local.loadReports()
        if (pending.isEmpty()) return emptyList()
        val owners = hashMapOf<String, String>()
        healthConnect.loadReports().forEach { report -> report.entries.forEach { owners[it.id] = report.id } }
        val saved = pending.filter { report ->
            // An entry id another report uses (files imported before they got fresh ids) would
            // overwrite that report's value, so this one stays in the app.
            val ownIds = report.entries.all { owners.getOrPut(it.id) { report.id } == report.id }
            ownIds && succeeds("Couldn't move a report to Health Connect yet") { healthConnect.save(report) }
        }
        if (saved.isEmpty()) return pending
        val stored = healthConnect.loadReports().associate { report -> report.id to report.entries.mapTo(hashSetOf()) { it.id } }
        val moved = saved.filter { report ->
            val complete = stored[report.id].orEmpty().containsAll(report.entries.filter { it.hasResult }.map { it.id })
            if (!complete) Log.w(TAG, "Health Connect didn't return a report it accepted; keeping the local copy")
            complete && succeeds("Couldn't remove a moved report's local copy yet") { local.delete(report) }
        }
        return pending - moved.toSet()
    }

    /** Runs [action], logging a failure instead of throwing it. */
    private suspend fun succeeds(failure: String, action: suspend () -> Unit): Boolean = try {
        action()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, failure, e)
        false
    }

    suspend fun save(report: LabReport) {
        store().save(report)
        reload()
    }

    /** Saves several reports (an imported transfer file), reloads once, and returns how many couldn't be saved. */
    suspend fun saveAll(reports: List<LabReport>): Int {
        val target = store()
        val failed = reports.count { report -> !succeeds("Couldn't save an imported report") { target.save(report) } }
        reload()
        return failed
    }

    /**
     * Replaces [old] with [updated] (edit flow: same id, rewritten entries). The new version
     * is saved first, so a failed save — which throws — or a process death leaves the old one.
     */
    suspend fun replace(old: LabReport, updated: LabReport): ReplaceOutcome {
        val target = store()
        val waiting = target === healthConnect && local.loadReports().any { it.id == old.id }
        val removed = if (waiting) {
            replaceWaiting(old, updated)
        } else {
            target.save(updated)
            succeeds("Couldn't remove what an edit dropped from a report") { target.removeDropped(old, updated) }
        }
        reload()
        return if (removed) ReplaceOutcome.REPLACED else ReplaceOutcome.SAVED_WITH_LEFTOVERS
    }

    /**
     * Edits a report waiting to move into Health Connect through its local copy, which the next
     * move would otherwise write over the edit. Saving there first means a failure changes nothing,
     * and the move's own check keeps the edit from overwriting a record another report uses.
     * Returns whether everything the new version dropped is gone.
     */
    private suspend fun replaceWaiting(old: LabReport, updated: LabReport): Boolean {
        local.save(updated)
        return succeeds("Couldn't remove what an edit dropped from a waiting report") {
            local.removeDropped(old, updated)
            // A move only adds, so it would leave what an earlier one already stored.
            healthConnect.loadReports().firstOrNull { it.id == old.id }?.let { healthConnect.removeDropped(it, updated) }
        }
    }

    /** Deletes [report] and reloads; throws if it couldn't be removed. */
    suspend fun delete(report: LabReport) {
        if (deleteAll(listOf(report)) > 0) throw IOException("Couldn't delete a report")
    }

    /**
     * Deletes [reports] one by one, reloads once, and returns how many couldn't be removed.
     * While Health Connect is in use a report can be in both stores, and a copy left in the app
     * would simply move back. Health Connect keeps one record per entry id, which a waiting report
     * may share with another report, so only the records it lists under this report's id go.
     */
    suspend fun deleteAll(reports: List<LabReport>): Int {
        val target = store()
        var stored: List<LabReport>? = null
        val failed = reports.count { report ->
            !succeeds("Couldn't delete a report") {
                if (target === healthConnect) {
                    val inHealthConnect = stored ?: healthConnect.loadReports().also { stored = it }
                    inHealthConnect.firstOrNull { it.id == report.id }?.let { healthConnect.delete(it) }
                }
                local.delete(report)
            }
        }
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
