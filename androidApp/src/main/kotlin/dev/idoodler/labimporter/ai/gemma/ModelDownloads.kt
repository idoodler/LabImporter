package dev.idoodler.labimporter.ai.gemma

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.idoodler.labimporter.R
import java.io.File

/** Where a model download stands, as [ModelDownloads.status] reports it. */
sealed interface DownloadStatus {
    data class Running(val bytes: Long, val totalBytes: Long?) : DownloadStatus

    /** Paused until there is Wi-Fi ([forWifi]) or any connection. */
    data class Waiting(val bytes: Long, val totalBytes: Long?, val forWifi: Boolean) : DownloadStatus

    data object Successful : DownloadStatus

    data class Failed(val insufficientSpace: Boolean, val detail: String) : DownloadStatus

    /** The system no longer knows the download (e.g. cleared from its list). */
    data object Missing : DownloadStatus
}

/** The model download as [GemmaAi] drives it — a seam, so its states are testable without the network. */
interface ModelDownloads {
    /** Starts fetching [GemmaModel.URL] into [destination]; returns the download's id. */
    fun start(destination: File, allowMobileData: Boolean): Long

    fun status(id: Long): DownloadStatus

    /** Stops and forgets the download, deleting what it fetched unless the file was moved away. */
    fun remove(id: Long)
}

/**
 * **The app's only network access**: Android's download manager fetches the
 * pinned [GemmaModel.URL] — a plain GET carrying nothing about the user, no
 * lab data, no identifiers — and keeps going in the background, on Wi-Fi only
 * unless the user allows mobile data, with the system's progress
 * notification. `NetworkGuardrailTest` fails the build if any other code
 * reaches for the network.
 */
class SystemModelDownloads(private val context: Context) : ModelDownloads {
    private val manager: DownloadManager = context.getSystemService(DownloadManager::class.java)

    override fun start(destination: File, allowMobileData: Boolean): Long {
        val request = DownloadManager.Request(GemmaModel.URL.toUri())
            .setTitle(context.getString(R.string.model_download_notification))
            .setDescription(GemmaModel.NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(android.net.Uri.fromFile(destination))
            .setAllowedOverMetered(allowMobileData)
            .setAllowedOverRoaming(false)
        return manager.enqueue(request)
    }

    override fun status(id: Long): DownloadStatus {
        manager.query(DownloadManager.Query().setFilterById(id))?.use { row ->
            if (!row.moveToFirst()) return DownloadStatus.Missing
            val bytes = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 }
            val reason = row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.Successful
                DownloadManager.STATUS_FAILED ->
                    DownloadStatus.Failed(reason == DownloadManager.ERROR_INSUFFICIENT_SPACE, "DownloadManager: error $reason")
                DownloadManager.STATUS_PAUSED -> when (reason) {
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> DownloadStatus.Waiting(bytes, total, forWifi = true)
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> DownloadStatus.Waiting(bytes, total, forWifi = false)
                    else -> DownloadStatus.Running(bytes, total) // Retrying shortly or paused by the system.
                }
                else -> DownloadStatus.Running(bytes, total)
            }
        }
        return DownloadStatus.Missing
    }

    override fun remove(id: Long) {
        manager.remove(id)
    }
}

/**
 * The download manager says a download finished — also while LabImporter is
 * closed (the system starts the app for it). Checking 2.6 GB takes longer
 * than a broadcast may run, so it goes to [ModelInstallJob]. Only the
 * download manager can send this (`SEND_DOWNLOAD_COMPLETED_INTENTS`).
 */
class ModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        ModelInstallJob.schedule(context)
    }
}
