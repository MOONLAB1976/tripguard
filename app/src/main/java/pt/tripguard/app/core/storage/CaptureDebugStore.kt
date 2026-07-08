package pt.tripguard.app.core.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class CaptureDebugStore(context: Context) {
    private val prefs = context.getSharedPreferences("tripguard", Context.MODE_PRIVATE)

    fun append(entry: CaptureDebugEntry) {
        val current = load().toMutableList()
        current.add(0, entry)
        val trimmed = current.take(MAX_ENTRIES)
        val json = JSONArray()
        trimmed.forEach { item ->
            json.put(
                JSONObject()
                    .put("timestampMs", item.timestampMs)
                    .put("packageName", item.packageName)
                    .put("sourceHint", item.sourceHint)
                    .put("stage", item.stage)
                    .put("detail", item.detail)
                    .put("excerpt", item.excerpt)
            )
        }
        prefs.edit().putString(KEY_DEBUG, json.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_DEBUG).apply()
    }

    fun load(): List<CaptureDebugEntry> {
        val raw = prefs.getString(KEY_DEBUG, null) ?: return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    CaptureDebugEntry(
                        timestampMs = item.optLong("timestampMs"),
                        packageName = item.optString("packageName").ifBlank { null },
                        sourceHint = item.optString("sourceHint"),
                        stage = item.optString("stage"),
                        detail = item.optString("detail"),
                        excerpt = item.optString("excerpt").ifBlank { null }
                    )
                )
            }
        }
    }

    companion object {
        private const val KEY_DEBUG = "capture_debug_v1"
        private const val MAX_ENTRIES = 40
    }
}

data class CaptureDebugEntry(
    val timestampMs: Long,
    val packageName: String?,
    val sourceHint: String,
    val stage: String,
    val detail: String,
    val excerpt: String?
)
