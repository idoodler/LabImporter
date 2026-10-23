package dev.idoodler.labimporter.ai

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ai.gemma.ModelDownloadNotifications
import dev.idoodler.labimporter.ai.gemma.ModelDownloadReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The download's end, while LabImporter isn't on screen: the install job and what the user is told. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class ModelDownloadNotificationsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun aCompletedDownloadSchedulesTheInstallJob() {
        ModelDownloadReceiver().onReceive(context, Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        val jobs = context.getSystemService(JobScheduler::class.java).allPendingJobs
        assertEquals(1, jobs.size)
        assertEquals("dev.idoodler.labimporter.ai.gemma.ModelInstallJob", jobs.single().service.className)
    }

    @Test
    fun notificationsSayReadyOrWhyNot() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = ModelDownloadNotifications(context)

        notifications.show(ModelEvent.Installed)
        val ready = shadowOf(manager).allNotifications.single()
        assertEquals(context.getString(R.string.model_ready_title), ready.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals(context.getString(R.string.model_ready_text), ready.extras.getString(NotificationCompat.EXTRA_TEXT))
        assertTrue(ready.contentIntent != null)

        // The same notification is replaced, not stacked.
        notifications.show(ModelEvent.Failed(AiProblem.NOT_ENOUGH_STORAGE))
        val failed = shadowOf(manager).allNotifications.single()
        assertEquals(context.getString(R.string.model_failed_title), failed.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals(context.getString(R.string.ai_problem_storage), failed.extras.getString(NotificationCompat.EXTRA_TEXT))

        notifications.cancel()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun withoutPermissionNothingIsPosted() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ModelDownloadNotifications(context).show(ModelEvent.Installed)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
