package pt.tripguard.app.core.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RemoteAdviceStore(
    context: Context
) {
    private val prefs = context.getSharedPreferences("tripguard", Context.MODE_PRIVATE)

    fun save(advice: RemoteAdvice) {
        val json = JSONObject()
            .put("summary", advice.summary)
            .put("riskLevel", advice.riskLevel)
            .put("recommendations", JSONArray(advice.recommendations))
            .put("receivedAtMs", advice.receivedAtMs)

        prefs.edit()
            .putString(KEY_REMOTE_ADVICE, json.toString())
            .putLong(KEY_LAST_SUCCESS_MS, advice.receivedAtMs)
            .putString(KEY_LAST_ERROR, "")
            .apply()
    }

    fun saveError(message: String) {
        prefs.edit()
            .putString(KEY_LAST_ERROR, message.take(240))
            .putLong(KEY_LAST_ATTEMPT_MS, System.currentTimeMillis())
            .apply()
    }

    fun markAttempt() {
        prefs.edit().putLong(KEY_LAST_ATTEMPT_MS, System.currentTimeMillis()).apply()
    }

    fun read(): RemoteAdvice? {
        val raw = prefs.getString(KEY_REMOTE_ADVICE, null) ?: return null
        val json = JSONObject(raw)
        val recommendations = json.optJSONArray("recommendations")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }
        } ?: emptyList()

        return RemoteAdvice(
            summary = json.optString("summary", "Sem conselho remoto ainda."),
            riskLevel = json.optString("riskLevel", "unknown"),
            recommendations = recommendations,
            receivedAtMs = json.optLong("receivedAtMs", 0L)
        )
    }

    fun statusText(config: TripAdviceApiConfig): String {
        val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT_MS, 0L)
        val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS_MS, 0L)
        val lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty()
        val state = when {
            !config.enabled -> "desligada"
            !config.canRun -> "configuracao incompleta"
            else -> "ativa a cada ${config.intervalHours} horas"
        }

        return buildString {
            appendLine("API de conselho: $state")
            appendLine("Endpoint: ${config.endpointUrl.ifBlank { "por configurar" }}")
            appendLine("Ultima tentativa: ${lastAttempt.takeIf { it > 0L } ?: "nunca"}")
            appendLine("Ultimo sucesso: ${lastSuccess.takeIf { it > 0L } ?: "nunca"}")
            if (lastError.isNotBlank()) {
                append("Ultimo erro: $lastError")
            }
        }.trim()
    }

    companion object {
        private const val KEY_REMOTE_ADVICE = "trip_advice_api_remote_advice"
        private const val KEY_LAST_ATTEMPT_MS = "trip_advice_api_last_attempt_ms"
        private const val KEY_LAST_SUCCESS_MS = "trip_advice_api_last_success_ms"
        private const val KEY_LAST_ERROR = "trip_advice_api_last_error"
    }
}
