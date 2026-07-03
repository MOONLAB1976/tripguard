package pt.tripguard.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateManager {
    const val KEY_UPDATE_METADATA_URL = "update_metadata_url"
    const val KEY_LAST_UPDATE_APK_URL = "last_update_apk_url"
    const val KEY_LAST_UPDATE_STATUS = "last_update_status"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    fun loadMetadataUrl(context: Context): String =
        context.getSharedPreferences(DiagnosticsExporter.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_UPDATE_METADATA_URL, "")
            .orEmpty()

    fun saveMetadataUrl(context: Context, url: String) {
        context.getSharedPreferences(DiagnosticsExporter.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UPDATE_METADATA_URL, url.trim())
            .apply()
    }

    fun loadLastApkUrl(context: Context): String =
        context.getSharedPreferences(DiagnosticsExporter.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_UPDATE_APK_URL, "")
            .orEmpty()

    fun rememberUpdateInfo(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(DiagnosticsExporter.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_UPDATE_APK_URL, info.apkUrl)
            .apply()
    }

    fun loadStatus(context: Context): String =
        context.getSharedPreferences(DiagnosticsExporter.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_UPDATE_STATUS, "Atualizacao online: por configurar")
            .orEmpty()

    fun saveStatus(context: Context, status: String) {
        context.getSharedPreferences(DiagnosticsExporter.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_UPDATE_STATUS, status)
            .apply()
    }

    fun fetchUpdateInfo(metadataUrl: String): UpdateInfo {
        val connection = (URL(metadataUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
        }

        connection.inputStream.bufferedReader().use { reader ->
            val json = JSONObject(reader.readText())
            return UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                notes = json.optString("notes")
            )
        }
    }

    fun downloadApk(context: Context, apkUrl: String): File {
        val targetDir = File(context.cacheDir, "update_apks").apply { mkdirs() }
        val targetFile = File(targetDir, "TripGuard-update.apk")
        val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30000
            readTimeout = 30000
        }

        connection.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return targetFile
    }
}
