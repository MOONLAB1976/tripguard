package pt.tripguard.app.core.api

import android.content.Context

data class TripAdviceApiConfig(
    val enabled: Boolean,
    val endpointUrl: String,
    val apiKey: String,
    val intervalHours: Long
) {
    val canRun: Boolean
        get() = enabled && endpointUrl.isNotBlank() && (
            endpointUrl.startsWith("https://", ignoreCase = true) ||
                endpointUrl.startsWith("http://192.168.", ignoreCase = true) ||
                endpointUrl.startsWith("http://10.", ignoreCase = true) ||
                endpointUrl.startsWith("http://172.", ignoreCase = true) ||
                endpointUrl.startsWith("http://127.0.0.1", ignoreCase = true) ||
                endpointUrl.startsWith("http://localhost", ignoreCase = true)
            )

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 3L

        private const val KEY_ENABLED = "trip_advice_api_enabled"
        private const val KEY_ENDPOINT_URL = "trip_advice_api_endpoint_url"
        private const val KEY_API_KEY = "trip_advice_api_key"

        fun read(context: Context): TripAdviceApiConfig {
            val prefs = context.getSharedPreferences("tripguard", Context.MODE_PRIVATE)
            return TripAdviceApiConfig(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                endpointUrl = prefs.getString(KEY_ENDPOINT_URL, "").orEmpty().trim(),
                apiKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim(),
                intervalHours = DEFAULT_INTERVAL_HOURS
            )
        }
    }
}
