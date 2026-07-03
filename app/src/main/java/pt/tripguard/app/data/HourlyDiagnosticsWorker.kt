package pt.tripguard.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class HourlyDiagnosticsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!DiagnosticsSyncScheduler.isEnabled(applicationContext)) {
            return Result.success()
        }

        val diagnostics = DiagnosticsStore(applicationContext)
        return runCatching {
            val linkedPath = DiagnosticsExporter.exportDiagnosticsToLinkedFolder(applicationContext)
                ?: return Result.retry()
            DiagnosticsExporter.rememberAutoExport(applicationContext, linkedPath)
            diagnostics.append("AUTO_EXPORT", "Guardado automaticamente em $linkedPath")
            Result.success()
        }.getOrElse { error ->
            diagnostics.append("AUTO_EXPORT", "Falhou exportacao automatica: ${error.message ?: error.javaClass.simpleName}")
            Result.retry()
        }
    }
}
