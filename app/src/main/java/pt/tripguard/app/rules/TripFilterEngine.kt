package pt.tripguard.app.rules

object TripFilterEngine {
    fun evaluate(offer: TripOffer, config: FilterConfig = FilterConfig.default()): EvaluationResult {
        val reasons = mutableListOf<String>()
        val zoneTexts = listOfNotNull(offer.pickupAddress, offer.destinationAddress).joinToString(" ").lowercase()

        listOfNotNull(offer.pickupPostalCode, offer.destinationPostalCode).forEach { postalCode ->
            if (config.blockedPostalPrefixes.any(postalCode::startsWith)) {
                reasons += "Blocked postal zone: $postalCode"
            }
        }

        config.blockedZoneKeywords.firstOrNull { keyword -> zoneTexts.contains(keyword) }?.let { keyword ->
            reasons += "Blocked zone keyword: $keyword"
        }

        offer.fareEur?.let { fare ->
            if (fare < config.minimumFareEur) {
                reasons += "Fare below minimum: ${"%.2f".format(fare)} EUR"
            }
        }

        offer.pickupDistanceKm?.let { pickupKm ->
            if (pickupKm > config.maximumPickupKm) {
                reasons += "Pickup too far: ${"%.1f".format(pickupKm)} km"
            }
        }

        offer.tripDistanceKm?.let { tripKm ->
            if (tripKm > config.maximumTripKm) {
                reasons += "Trip too long for current baseline: ${"%.1f".format(tripKm)} km"
            }
        }

        val totalKmForYield = listOfNotNull(offer.pickupDistanceKm, offer.tripDistanceKm).sum()
        if (totalKmForYield > 0.0 && offer.fareEur != null) {
            val eurPerKm = offer.fareEur / totalKmForYield.coerceAtLeast(0.1)
            if (eurPerKm < config.minimumEurPerKm) {
                reasons += "Low yield: ${"%.2f".format(eurPerKm)} EUR/km"
            }
        }

        val totalDurationMin = listOfNotNull(offer.pickupDurationMin, offer.tripDurationMin).sum()
        if (totalDurationMin > 0.0 && offer.fareEur != null) {
            val eurPerHour = offer.fareEur / (totalDurationMin / 60.0).coerceAtLeast(0.1)
            if (eurPerHour < config.minimumEurPerHour) {
                reasons += "Low hourly return: ${"%.2f".format(eurPerHour)} EUR/h"
            }
        }

        return when {
            reasons.isNotEmpty() -> EvaluationResult(OfferDecision.REJECT, reasons)
            offer.fareEur == null || offer.pickupDistanceKm == null -> {
                EvaluationResult(OfferDecision.REVIEW, listOf("Need more screen data before auto action"))
            }
            else -> {
                val summary = buildList {
                    add("Offer matches baseline filters")
                    add("App: ${offer.sourceApp.name}")
                    offer.eurPerKm()?.let { add("Yield: ${"%.2f".format(it)} EUR/km") }
                    offer.eurPerHour()?.let { add("Hourly: ${"%.2f".format(it)} EUR/h") }
                    offer.pickupPostalCode?.let { add("Pickup zone: $it") }
                    offer.destinationPostalCode?.let { add("Destination zone: $it") }
                }
                EvaluationResult(OfferDecision.ACCEPT, summary)
            }
        }
    }
}
