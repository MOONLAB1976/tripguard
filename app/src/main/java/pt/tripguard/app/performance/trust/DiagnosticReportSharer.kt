package pt.tripguard.app.performance.trust

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DiagnosticReportSharer(
    private val context: Context
) {
    fun createShareIntent(): Intent {
        val syncIdentity = SyncIdentityStore(context).read()
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val reportFile = File(
            reportsDir,
            "tripguard-${syncIdentity.reference.safeFilePart()}-${syncIdentity.deviceId}-${fileTimestamp()}.md"
        )
        reportFile.writeText(DiagnosticReportBuilder(context).buildMarkdown())

        val filesToShare = mutableListOf<File>()
        filesToShare.add(reportFile)

        // Encontrar as capturas de ecrã para partilhar
        val screenshotsDir = File(context.cacheDir, "screenshots")
        if (screenshotsDir.exists()) {
            val screenshotFiles = screenshotsDir.listFiles()?.sortedBy { it.lastModified() }
            if (screenshotFiles != null) {
                // Limitar às 10 capturas mais recentes para não sobrecarregar
                screenshotFiles.takeLast(10).forEach { srcFile ->
                    val destFile = File(reportsDir, srcFile.name)
                    runCatching {
                        srcFile.copyTo(destFile, overwrite = true)
                        filesToShare.add(destFile)
                    }
                }
            }
        }

        val uris = ArrayList(filesToShare.map { file ->
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        })

        val sendIntent = if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_SUBJECT, "TripGuard diagnostics ${fileTimestamp()}")
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_SUBJECT, "TripGuard diagnostics ${fileTimestamp()}")
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        return Intent.createChooser(sendIntent, "Enviar relatorio TripGuard")
    }

    private fun fileTimestamp(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    private fun String.safeFilePart(): String =
        lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9._-]+"""), "-")
            .trim('-')
            .ifBlank { "sem-referencia" }
}
