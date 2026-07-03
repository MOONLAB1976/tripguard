package pt.tripguard.app.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tripguard", Context.MODE_PRIVATE)
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun append(stage: String, message: String) {
        val current = load().toMutableList()
        current.add(0, "${formatter.format(Date())} [$stage] $message")
        val trimmed = current.take(MAX_LINES)
        prefs.edit().putString(KEY_LOG, trimmed.joinToString("\n")).apply()
    }

    fun loadText(): String = load().joinToString("\n")

    fun clear() {
        prefs.edit().remove(KEY_LOG).apply()
    }

    private fun load(): List<String> =
        prefs.getString(KEY_LOG, null)
            ?.lines()
            ?.map { it.trimEnd() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    companion object {
        private const val KEY_LOG = "diagnostics_log_v1"
        private const val MAX_LINES = 80
    }
}
