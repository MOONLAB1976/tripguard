package pt.tripguard.app.core.api

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

class TripAdviceJobService : JobService() {
    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        thread(name = "TripAdviceJob") {
            val shouldRetry = runAdviceSync()
            if (!stopped) {
                jobFinished(params, shouldRetry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true
    }

    private fun runAdviceSync(): Boolean {
        val config = TripAdviceApiConfig.read(this)
        val store = RemoteAdviceStore(this)

        if (!config.canRun) {
            TripAdviceScheduler.cancel(this)
            return false
        }

        return try {
            store.markAttempt()
            val payload = TripAdvicePayloadBuilder(this).build()
            val advice = TripAdviceApiClient().requestAdvice(config, payload)
            store.save(advice)
            false
        } catch (error: Exception) {
            store.saveError(error.message ?: error::class.java.simpleName)
            true
        }
    }
}
