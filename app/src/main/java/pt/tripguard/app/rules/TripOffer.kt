package pt.tripguard.app.rules

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
    val destinationPostalCode: String?
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
        val distance = totalDistanceKm() ?: return null
        return fare / distance.coerceAtLeast(0.1)
    }

    fun eurPerHour(): Double? {
        val fare = fareEur ?: return null
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
    val maximumPickupKm: Double,
    val maximumTripKm: Double,
    val blockedPostalPrefixes: Set<String>,
    val blockedZoneKeywords: Set<String>
) {
    companion object {
        fun default(): FilterConfig = FilterConfig(
            minimumFareEur = 6.5,
            minimumEurPerKm = 1.10,
            minimumEurPerHour = 18.0,
            maximumPickupKm = 4.0,
            maximumTripKm = 25.0,
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
