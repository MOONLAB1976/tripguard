package pt.tripguard.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pt.tripguard.app.rules.EvaluationResult
import pt.tripguard.app.rules.OfferDecision
import pt.tripguard.app.rules.SourceApp
import pt.tripguard.app.rules.TripHistoryEntry
import pt.tripguard.app.rules.TripOffer

class TripHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("tripguard", Context.MODE_PRIVATE)

    fun save(entry: TripHistoryEntry) {
        val current = load()
            .filterNot { existing ->
                existing.offer.sourceApp == entry.offer.sourceApp &&
                    existing.offer.fareEur == entry.offer.fareEur &&
                    existing.offer.pickupDistanceKm == entry.offer.pickupDistanceKm &&
                    existing.offer.tripDistanceKm == entry.offer.tripDistanceKm &&
                    existing.offer.pickupAddress == entry.offer.pickupAddress &&
                    existing.offer.destinationAddress == entry.offer.destinationAddress
            }
            .toMutableList()
        current.add(0, entry)
        val trimmed = current.take(MAX_ENTRIES)
        val json = JSONArray()
        trimmed.forEach { item ->
            json.put(
                JSONObject()
                    .put("capturedAtMs", item.capturedAtMs)
                    .put("rawText", item.offer.rawText)
                    .put("sourceApp", item.offer.sourceApp.name)
                    .put("fareEur", item.offer.fareEur)
                    .put("pickupDurationMin", item.offer.pickupDurationMin)
                    .put("pickupDistanceKm", item.offer.pickupDistanceKm)
                    .put("tripDurationMin", item.offer.tripDurationMin)
                    .put("tripDistanceKm", item.offer.tripDistanceKm)
                    .put("pickupAddress", item.offer.pickupAddress)
                    .put("destinationAddress", item.offer.destinationAddress)
                    .put("pickupPostalCode", item.offer.pickupPostalCode)
                    .put("destinationPostalCode", item.offer.destinationPostalCode)
                    .put("decision", item.result.decision.name)
                    .put("reasons", JSONArray(item.result.reasons))
            )
        }
        prefs.edit().putString(KEY_HISTORY, json.toString()).apply()
    }

    fun load(): List<TripHistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    TripHistoryEntry(
                        capturedAtMs = item.optLong("capturedAtMs"),
                        offer = TripOffer(
                            rawText = item.optString("rawText"),
                            sourceApp = item.optString("sourceApp")
                                .takeIf { it.isNotBlank() }
                                ?.let { SourceApp.valueOf(it) }
                                ?: SourceApp.UNKNOWN,
                            fareEur = item.optDoubleOrNull("fareEur"),
                            pickupDurationMin = item.optDoubleOrNull("pickupDurationMin"),
                            pickupDistanceKm = item.optDoubleOrNull("pickupDistanceKm"),
                            tripDurationMin = item.optDoubleOrNull("tripDurationMin"),
                            tripDistanceKm = item.optDoubleOrNull("tripDistanceKm"),
                            pickupAddress = item.optString("pickupAddress").ifBlank { null },
                            destinationAddress = item.optString("destinationAddress").ifBlank { null },
                            pickupPostalCode = item.optString("pickupPostalCode").ifBlank { null },
                            destinationPostalCode = item.optString("destinationPostalCode").ifBlank { null }
                        ),
                        result = EvaluationResult(
                            decision = OfferDecision.valueOf(item.optString("decision", OfferDecision.REVIEW.name)),
                            reasons = item.optStringArray("reasons")
                        )
                    )
                )
            }
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (isNull(key)) return null
        return optDouble(key)
    }

    private fun JSONObject.optStringArray(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
        }
    }

    companion object {
        private const val KEY_HISTORY = "trip_history_v2"
        private const val MAX_ENTRIES = 10
    }
}
