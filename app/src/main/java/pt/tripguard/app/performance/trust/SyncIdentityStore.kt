package pt.tripguard.app.performance.trust

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

class SyncIdentityStore(
    private val context: Context
) {
    private val prefs = context.getSharedPreferences("tripguard", Context.MODE_PRIVATE)

    fun read(): SyncIdentity {
        val existingId = prefs.getString(KEY_DEVICE_ID, null)
        val deviceId = existingId ?: buildDefaultDeviceId().also { generatedId ->
            prefs.edit().putString(KEY_DEVICE_ID, generatedId).apply()
        }

        return SyncIdentity(
            deviceId = deviceId,
            deviceLabel = prefs.getString(KEY_DEVICE_LABEL, defaultDeviceLabel()).orEmpty(),
            reference = prefs.getString(KEY_REFERENCE, DEFAULT_REFERENCE).orEmpty().ifBlank { DEFAULT_REFERENCE }
        )
    }

    private fun buildDefaultDeviceId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "${Build.MANUFACTURER}-${Build.MODEL}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${context.packageName}:$androidId".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
        return "tg-$digest"
    }

    private fun defaultDeviceLabel(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)

    companion object {
        private const val KEY_DEVICE_ID = "sync_device_id"
        private const val KEY_DEVICE_LABEL = "sync_device_label"
        private const val KEY_REFERENCE = "sync_reference"
        private const val DEFAULT_REFERENCE = "sem-referencia"
    }
}
