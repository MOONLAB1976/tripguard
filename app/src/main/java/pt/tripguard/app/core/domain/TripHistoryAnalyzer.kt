package pt.tripguard.app.core.domain

import kotlin.math.roundToInt

data class HistoryAnalysis(
    val totalOffers: Int,
    val acceptedOffers: Int,
    val rejectedOffers: Int,
    val reviewOffers: Int,
    val averageFare: Double,
    val averagePickupKm: Double,
    val averageTripKm: Double,
    val averageTotalKm: Double,
    val averageEurPerKm: Double,
    val averageEurPerHour: Double,
    val bestPostalPrefix: String?,
    val worstPostalPrefix: String?,
    val suggestedBlockedPrefixes: List<String>,
    val advisorNotes: List<String>
)

object TripHistoryAnalyzer {
    fun analyze(entries: List<TripHistoryEntry>): HistoryAnalysis {
        val usable = entries.take(10)
        val accepted = usable.count { it.result.decision == OfferDecision.ACCEPT }
        val rejected = usable.count { it.result.decision == OfferDecision.REJECT }
        val review = usable.count { it.result.decision == OfferDecision.REVIEW }
        val fares = usable.mapNotNull { it.offer.fareEur }
        val pickupKm = usable.mapNotNull { it.offer.pickupDistanceKm }
        val tripKm = usable.mapNotNull { it.offer.tripDistanceKm }
        val totalKm = usable.mapNotNull { it.offer.totalDistanceKm() }
        val eurPerKmValues = usable.mapNotNull { it.offer.eurPerKm() }
        val eurPerHourValues = usable.mapNotNull { it.offer.eurPerHour() }

        val postalScores = usable
            .mapNotNull { entry ->
                val prefix = entry.offer.pickupPostalCode?.take(4)
                    ?: entry.offer.destinationPostalCode?.take(4)
                    ?: return@mapNotNull null
                val fare = entry.offer.fareEur ?: 0.0
                val distance = entry.offer.totalDistanceKm() ?: 0.0
                val score = fare - (distance * 1.2)
                prefix to score
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.average() }

        val sortedZones = postalScores.entries.sortedBy { it.value }
        val suggestedBlocked = sortedZones
            .filter { it.value < 4.0 }
            .map { it.key }
            .take(3)

        val advisorNotes = buildList {
            if (usable.size < 5) {
                add("Ainda existem poucas viagens guardadas. As recomendacoes ficam melhores quando houver 10 registos.")
            }
            if (eurPerKmValues.averageOrZero() < 1.10) {
                add("O retorno medio por quilometro esta baixo. Convem apertar recolhas longas e zonas fracas.")
            }
            if (eurPerHourValues.averageOrZero() < 18.0) {
                add("O retorno medio por hora esta baixo. Devemos evitar viagens longas com tarifa total fraca.")
            }
            if (suggestedBlocked.isNotEmpty()) {
                add("Codigos postais com pior sinal recente: ${suggestedBlocked.joinToString(", ")}.")
            }
            if (rejected > accepted) {
                add("Ha mais rejeicoes do que aceitacoes nas ultimas ofertas. Vale a pena rever limites para nao perder viagens boas.")
            }
        }

        return HistoryAnalysis(
            totalOffers = usable.size,
            acceptedOffers = accepted,
            rejectedOffers = rejected,
            reviewOffers = review,
            averageFare = fares.averageOrZero(),
            averagePickupKm = pickupKm.averageOrZero(),
            averageTripKm = tripKm.averageOrZero(),
            averageTotalKm = totalKm.averageOrZero(),
            averageEurPerKm = eurPerKmValues.averageOrZero(),
            averageEurPerHour = eurPerHourValues.averageOrZero(),
            bestPostalPrefix = postalScores.maxByOrNull { it.value }?.key,
            worstPostalPrefix = postalScores.minByOrNull { it.value }?.key,
            suggestedBlockedPrefixes = suggestedBlocked,
            advisorNotes = advisorNotes
        )
    }

    fun formatForUi(analysis: HistoryAnalysis): String {
        if (analysis.totalOffers == 0) {
            return "Ainda nao existe historico capturado."
        }

        val blocked = analysis.suggestedBlockedPrefixes.ifEmpty { listOf("nenhum ainda") }.joinToString(", ")
        val bestZone = analysis.bestPostalPrefix ?: "desconhecido"
        val worstZone = analysis.worstPostalPrefix ?: "desconhecido"
        val advisor = analysis.advisorNotes.ifEmpty { listOf("Sem alertas por agora.") }.joinToString("\n")

        return buildString {
            appendLine("Ultimas ${analysis.totalOffers} ofertas")
            appendLine("Aceites: ${analysis.acceptedOffers} | Rejeitadas: ${analysis.rejectedOffers} | Rever: ${analysis.reviewOffers}")
            appendLine("Tarifa media: ${analysis.averageFare.money()}")
            appendLine("Recolha media: ${analysis.averagePickupKm.km()} | Viagem media: ${analysis.averageTripKm.km()}")
            appendLine("Media: ${analysis.averageEurPerKm.money("EUR/km")} | ${analysis.averageEurPerHour.money("EUR/h")}")
            appendLine("Melhor codigo postal: $bestZone")
            appendLine("Pior codigo postal: $worstZone")
            appendLine("Sugestao de bloqueio: $blocked")
            append("Conselho IA:\n$advisor")
        }
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun Double.money(suffix: String = "EUR"): String =
        "${((this * 100.0).roundToInt() / 100.0).format2()} $suffix"

    private fun Double.km(): String = "${format1()} km"

    private fun Double.format1(): String = String.format("%.1f", this)

    private fun Double.format2(): String = String.format("%.2f", this)
}
