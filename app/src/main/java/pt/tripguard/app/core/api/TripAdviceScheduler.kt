package pt.tripguard.app.core.api

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object TripAdviceScheduler {
    private const val JOB_ID = 3003
    private const val MIN_PERIOD_MS = TripAdviceApiConfig.DEFAULT_INTERVAL_HOURS * 60L * 60L * 1000L

    fun ensureScheduled(context: Context) {
        val config = TripAdviceApiConfig.read(context)
        if (!config.canRun) {
            cancel(context)
            return
        }

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val alreadyScheduled = scheduler.allPendingJobs.any { it.id == JOB_ID }
        if (alreadyScheduled) return

        val component = ComponentName(context, TripAdviceJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(MIN_PERIOD_MS)
            .build()

        scheduler.schedule(jobInfo)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
