package dev.idoodler.labimporter.ai.gemma

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import dev.idoodler.labimporter.AppContainerOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Checks and installs the finished model download in the background ([dev.idoodler.labimporter.ai.GemmaAi.finishDownload]). */
class ModelInstallJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val ai = (application as AppContainerOwner).container.ai
        work = CoroutineScope(Dispatchers.Main).launch {
            try {
                ai.finishModelDownload()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    // The next app start (or completion broadcast) finishes it instead.
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 2001

        fun schedule(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ModelInstallJob::class.java)).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(true) else setOverrideDeadline(0)
            }.build()
            val result = context.getSystemService(JobScheduler::class.java).schedule(job)
            if (result != JobScheduler.RESULT_SUCCESS) Log.w("ModelInstallJob", "Couldn't schedule the model check ($result)")
        }
    }
}
