package dev.idoodler.labimporter.ai.gemma

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.idoodler.labimporter.MainActivity
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.ModelEvent
import dev.idoodler.labimporter.ai.messageRes

/**
 * Tells a user who isn't looking at LabImporter how the model download went:
 * checking the file, ready, or why it failed. The download's progress (and a
 * wait for Wi-Fi) is the system download manager's own notification; these
 * pick up from there. Nothing is shown while the app is on screen — it says
 * the same itself — and nothing without the notification permission.
 */
class ModelDownloadNotifications(private val context: Context) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Shows [event] (replacing the previous one). */
    fun show(event: ModelEvent) {
        if (!allowed()) return
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
        when (event) {
            ModelEvent.Verifying -> builder
                .setContentTitle(context.getString(R.string.model_verifying))
                .setProgress(0, 0, true)
                .setSilent(true)
                .setOngoing(true)
            ModelEvent.Installed -> builder
                .setContentTitle(context.getString(R.string.model_ready_title))
                .setContentText(context.getString(R.string.model_ready_text))
                .setAutoCancel(true)
            is ModelEvent.Failed -> {
                val reason = context.getString(event.problem.messageRes)
                builder
                    .setContentTitle(context.getString(R.string.model_failed_title))
                    .setContentText(reason)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                    .setAutoCancel(true)
            }
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    /** The app is on screen again and shows the state itself. */
    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun allowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.model_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.model_channel_description)
        manager.createNotificationChannel(channel) // Creating an existing channel only updates its name and description.
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "model_download"
        private const val NOTIFICATION_ID = 1001
    }
}
