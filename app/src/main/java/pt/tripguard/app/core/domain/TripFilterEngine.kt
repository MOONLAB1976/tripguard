package pt.tripguard.app.core.domain

import java.util.Locale

object TripFilterEngine {
    fun evaluate(offer: TripOffer, config: FilterConfig = FilterConfig.default()): EvaluationResult {
        val reasons = mutableListOf<String>()
        val zoneTexts = listOfNotNull(offer.pickupAddress, offer.destinationAddress).joinToString(" ").lowercase()

        listOfNotNull(offer.pickupPostalCode, offer.destinationPostalCode).forEach { postalCode ->
            if (config.blockedPostalPrefixes.any(postalCode::startsWith)) {
                reasons += "Zona postal bloqueada: $postalCode"
            }
        }

        config.blockedZoneKeywords.firstOrNull { keyword -> zoneTexts.contains(keyword) }?.let { keyword ->
            reasons += "Zona bloqueada por nome: $keyword"
        }

        offer.fareEur?.let { fare ->
            if (fare < config.minimumFareEur) {
                reasons += "Tarifa abaixo do minimo: ${fare.money()} EUR"
            }
        }

        offer.pickupDistanceKm?.let { pickupKm ->
            if (pickupKm > config.maximumPickupKm) {
                reasons += "Recolha demasiado longe: ${pickupKm.km()} km"
            }
        }

        offer.pickupDurationMin?.let { pickupMin ->
            if (pickupMin > config.maximumPickupDurationMin) {
                reasons += "Tempo de recolha demasiado alto: ${pickupMin.min()} min"
            }
        }

        offer.tripDistanceKm?.let { tripKm ->
            if (tripKm > config.maximumTripKm) {
                reasons += "Viagem demasiado longa para o baseline: ${tripKm.km()} km"
            }
        }

        offer.eurPerKm()?.let { eurPerKm ->
            if (eurPerKm < config.minimumEurPerKm) {
                reasons += "Rentabilidade baixa: ${eurPerKm.money()} EUR/km"
            }
        }

        offer.eurPerHour()?.let { eurPerHour ->
            if (eurPerHour < config.minimumEurPerHour) {
                reasons += "Retorno horario baixo: ${eurPerHour.money()} EUR/h"
            }
        }

        if (reasons.isNotEmpty()) {
            return EvaluationResult(OfferDecision.REJECT, reasons)
        }

        val eurPerKm = offer.eurPerKm()
        val eurPerHour = offer.eurPerHour()

        return when {
            offer.fareEur == null -> {
                EvaluationResult(OfferDecision.REVIEW, listOf("Falta tarifa para decidir com seguranca"))
            }
            offer.pickupDistanceKm == null -> {
                EvaluationResult(OfferDecision.REVIEW, listOf("Falta distancia de recolha para decidir com seguranca"))
            }
            eurPerKm == null -> {
                EvaluationResult(OfferDecision.REVIEW, listOf("Falta distancia total para calcular EUR/km"))
            }
            eurPerHour == null -> {
                EvaluationResult(OfferDecision.REVIEW, listOf("Falta tempo total para calcular EUR/h"))
            }
            // Média/Amarelo se for menor que os limites bons nas definições
            eurPerKm < config.goodEurPerKm || eurPerHour < config.goodEurPerHour -> {
                val reviewReasons = mutableListOf<String>()
                if (eurPerKm < config.goodEurPerKm) {
                    reviewReasons += "Abaixo do bom: ${eurPerKm.money()} EUR/km"
                }
                if (eurPerHour < config.goodEurPerHour) {
                    reviewReasons += "Abaixo do bom: ${eurPerHour.money()} EUR/h"
                }
                EvaluationResult(OfferDecision.REVIEW, reviewReasons)
            }
            else -> {
                val summary = buildList {
                    add("Oferta cumpre filtros base")
                    add("App: ${offer.sourceApp.name}")
                    add("Rentabilidade: ${eurPerKm.money()} EUR/km")
                    add("Horario: ${eurPerHour.money()} EUR/h")
                    offer.pickupPostalCode?.let { add("Zona recolha: $it") }
                    offer.destinationPostalCode?.let { add("Zona destino: $it") }
                }
                EvaluationResult(OfferDecision.ACCEPT, summary)
            }
        }
    }

    private fun Double.money(): String = String.format(Locale.US, "%.2f", this)

    private fun Double.km(): String = String.format(Locale.US, "%.1f", this)

    private fun Double.min(): String = String.format(Locale.US, "%.0f", this)
}
