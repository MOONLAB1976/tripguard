package pt.tripguard.app.core.domain

data class TripOffer(
    val rawText: String,
    val sourceApp: SourceApp,
    val fareEur: Double?,
    val pickupDurationMin: Double?,
    val pickupDistanceKm: Double?,
    val tripDurationMin: Double?,
    val tripDistanceKm: Double?,
    val pickupAddress: String?,
    val destinationAddress: String?,
    val pickupPostalCode: String?,
    val destinationPostalCode: String?,
    val stopsCount: Int? = null  // "1 paragem" / "2 paragens" — paragens intermédias
) {
    fun totalDistanceKm(): Double? {
        val total = listOfNotNull(pickupDistanceKm, tripDistanceKm).sum()
        return total.takeIf { it > 0.0 }
    }

    fun totalDurationMin(): Double? {
        val total = listOfNotNull(pickupDurationMin, tripDurationMin).sum()
        return total.takeIf { it > 0.0 }
    }

    fun eurPerKm(): Double? {
        val fare = fareEur ?: return null
        // Need at least trip distance to compute a meaningful EUR/km.
        // Using only pickup distance would give wildly inflated values.
        val tripKm = tripDistanceKm ?: return null
        val total = (pickupDistanceKm ?: 0.0) + tripKm
        return fare / total.coerceAtLeast(0.1)
    }

    fun eurPerHour(): Double? {
        val fare = fareEur ?: return null
        // If we know the trip exists (distance known) but don't know trip duration,
        // we cannot compute a reliable EUR/h — using only pickup time would inflate the value
        if (tripDistanceKm != null && tripDurationMin == null) return null
        val durationMin = totalDurationMin() ?: return null
        return fare / (durationMin / 60.0).coerceAtLeast(0.1)
    }
}

data class TripHistoryEntry(
    val capturedAtMs: Long,
    val offer: TripOffer,
    val result: EvaluationResult
)

data class FilterConfig(
    val minimumFareEur: Double,
    val minimumEurPerKm: Double,
    val minimumEurPerHour: Double,
    val goodEurPerKm: Double,
    val goodEurPerHour: Double,
    val maximumPickupKm: Double,
    val maximumPickupDurationMin: Double,
    val maximumTripKm: Double,
    val blockedPostalPrefixes: Set<String>,
    val blockedZoneKeywords: Set<String>
) {
    companion object {
        fun default(): FilterConfig = FilterConfig(
            minimumFareEur = 0.0,
            minimumEurPerKm = 1.10,
            goodEurPerKm = 1.50,
            minimumEurPerHour = 18.0,
            goodEurPerHour = 22.0,
            maximumPickupKm = 99.0,
            maximumPickupDurationMin = 99.0,
            maximumTripKm = 999.0,
            blockedPostalPrefixes = setOf("4400", "4410", "4420", "4430", "4435", "4440"),
            blockedZoneKeywords = setOf(
                "vila nova de gaia",
                "gaia",
                "mafamude",
                "oliveira do douro",
                "canidelo"
            )
        )
    }
}

enum class SourceApp {
    UBER,
    BOLT,
    UNKNOWN
}

enum class OfferDecision {
    ACCEPT,
    REJECT,
    REVIEW
}

data class EvaluationResult(
    val decision: OfferDecision,
    val reasons: List<String>
)
