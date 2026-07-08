package pt.tripguard.app.core.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pt.tripguard.app.BuildConfig
import pt.tripguard.app.core.storage.TripHistoryStore
import pt.tripguard.app.performance.trust.SyncIdentityStore

class TripAdvicePayloadBuilder(
    private val context: Context
) {
    fun build(): JSONObject {
        val syncIdentity = SyncIdentityStore(context).read()
        val history = TripHistoryStore(context).load()

        return JSONObject()
            .put("schema", "tripguard.advice_request.v1")
            .put("generated_at_ms", System.currentTimeMillis())
            .put("app", JSONObject()
                .put("package", BuildConfig.APPLICATION_ID)
                .put("version_name", BuildConfig.VERSION_NAME)
                .put("version_code", BuildConfig.VERSION_CODE)
            )
            .put("sync", JSONObject()
                .put("device_id", syncIdentity.deviceId)
                .put("device_label", syncIdentity.deviceLabel)
                .put("reference", syncIdentity.reference)
            )
            .put("privacy", JSONObject()
                .put("raw_screen_text_included", false)
                .put("full_addresses_included", false)
                .put("postal_prefix_only", true)
            )
            .put("offers", JSONArray().apply {
                history.take(10).forEach { entry ->
                    put(JSONObject()
                        .put("captured_at_ms", entry.capturedAtMs)
                        .put("source_app", entry.offer.sourceApp.name)
                        .put("fare_eur", entry.offer.fareEur)
                        .put("pickup_duration_min", entry.offer.pickupDurationMin)
                        .put("pickup_distance_km", entry.offer.pickupDistanceKm)
                        .put("trip_duration_min", entry.offer.tripDurationMin)
                        .put("trip_distance_km", entry.offer.tripDistanceKm)
                        .put("eur_per_km", entry.offer.eurPerKm())
                        .put("eur_per_hour", entry.offer.eurPerHour())
                        .put("pickup_postal_prefix", entry.offer.pickupPostalCode?.take(4))
                        .put("destination_postal_prefix", entry.offer.destinationPostalCode?.take(4))
                        .put("decision", entry.result.decision.name)
                        .put("reasons", JSONArray(entry.result.reasons))
                    )
                }
            })
    }
}
