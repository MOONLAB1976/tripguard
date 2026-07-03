package pt.tripguard.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsExporter {
    const val PREFS_NAME = "tripguard"
    const val KEY_EXPORT_FOLDER_URI = "export_folder_uri"
    const val KEY_LAST_EXPORT_PATH = "last_export_path"
    const val KEY_LAST_AUTO_EXPORT_AT = "last_auto_export_at"

    fun buildDiagnosticsReport(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val history = TripHistoryStore(context).load().take(10)
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("TripGuard diagnostico")
            appendLine("Gerado em: $exportedAt")
            appendLine("Pacote: ${prefs.getString("last_seen_package", "-").orEmpty()}")
            appendLine("Evento: ${prefs.getString("last_event_debug", "Sem evento").orEmpty()}")
            appendLine("Parse: ${prefs.getString("last_parse_debug", "Sem parse").orEmpty()}")
            appendLine()
            appendLine("Ultimo texto lido:")
            appendLine(prefs.getString("last_raw_text_sample", "Sem leitura tecnica ainda.").orEmpty())
            appendLine()
            appendLine("Historico de diagnostico:")
            appendLine(DiagnosticsStore(context).loadText().ifBlank { "Sem historico ainda." })
            appendLine()
            appendLine("Ultimas ofertas guardadas:")
            if (history.isEmpty()) {
                appendLine("Sem historico guardado.")
            } else {
                history.forEach { entry ->
                    val fare = entry.offer.fareEur?.let { String.format(Locale.US, "%.2f", it) } ?: "?"
                    val eurKm = entry.offer.eurPerKm()?.let { String.format(Locale.US, "%.2f", it) } ?: "?"
                    val eurHour = entry.offer.eurPerHour()?.let { String.format(Locale.US, "%.2f", it) } ?: "?"
                    appendLine(
                        "${entry.offer.sourceApp.name} | $fare EUR | $eurKm EUR/km | $eurHour EUR/h | " +
                            "${entry.offer.pickupPostalCode ?: "----"} -> ${entry.offer.destinationPostalCode ?: "----"} | ${entry.result.decision}"
                    )
                }
            }
        }
    }

    fun buildDiagnosticsFileName(now: Date = Date()): String =
        "tripguard_diagnostico_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now) +
            ".txt"

    fun loadLinkedExportFolderUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EXPORT_FOLDER_URI, null)
            ?.let(Uri::parse)

    fun loadLinkedExportFolderName(context: Context): String? {
        val uri = loadLinkedExportFolderUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment
    }

    fun rememberExportPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_EXPORT_PATH, path)
            .apply()
    }

    fun rememberAutoExport(context: Context, path: String) {
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_EXPORT_PATH, path)
            .putString(KEY_LAST_AUTO_EXPORT_AT, exportedAt)
            .apply()
    }

    fun exportDiagnosticsToLinkedFolder(context: Context): String? {
        val linkedUri = loadLinkedExportFolderUri(context) ?: return null
        val tree = DocumentFile.fromTreeUri(context, linkedUri)
            ?: error("Sem acesso a pasta ligada")
        val fileName = buildDiagnosticsFileName()
        val exportFile = tree.createFile("text/plain", fileName.removeSuffix(".txt"))
            ?: error("Nao consegui criar o ficheiro")
        context.contentResolver.openOutputStream(exportFile.uri)?.bufferedWriter()?.use { writer ->
            writer.write(buildDiagnosticsReport(context))
        } ?: error("Nao consegui abrir a pasta para escrita")
        return "Pasta ligada/$fileName"
    }

    fun saveDiagnosticsForCable(context: Context): String {
        val targetDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
            "diagnostics"
        ).apply { mkdirs() }
        val targetFile = File(targetDir, buildDiagnosticsFileName())
        targetFile.writeText(buildDiagnosticsReport(context))
        return targetFile.absolutePath
    }

    fun saveDiagnosticsToDownloads(context: Context): String {
        val fileName = buildDiagnosticsFileName()
        val fileText = buildDiagnosticsReport(context)
        val publicPath = "Download/TripGuard/$fileName"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/TripGuard")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Nao consegui criar ficheiro nos Downloads")
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(fileText)
            } ?: error("Nao consegui escrever o ficheiro")
        } else {
            val targetDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "TripGuard"
            ).apply { mkdirs() }
            File(targetDir, fileName).writeText(fileText)
        }

        return publicPath
    }
}
