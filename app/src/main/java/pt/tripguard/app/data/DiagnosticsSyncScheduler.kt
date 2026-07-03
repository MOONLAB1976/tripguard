package pt.tripguard.app.data

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object DiagnosticsSyncScheduler {
    const val KEY_AUTO_EXPORT_ENABLED = "auto_export_enabled"
    private const val UNIQUE_WORK_NAME = "tripguard_auto_export_hourly"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(DiagnosticsExporter.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_EXPORT_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(DiagnosticsExporter.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_EXPORT_ENABLED, enabled)
            .apply()
        if (enabled) {
            schedule(context)
        } else {
            cancel(context)
        }
    }

    fun ensureState(context: Context) {
        if (isEnabled(context)) {
            schedule(context)
        } else {
            cancel(context)
        }
    }

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<HourlyDiagnosticsWorker>(1, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
