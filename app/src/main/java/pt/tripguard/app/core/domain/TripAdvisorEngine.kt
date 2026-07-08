package pt.tripguard.app.core.domain

import java.util.Locale
import kotlin.math.roundToInt

data class TripAdvisorReport(
    val analysis: HistoryAnalysis,
    val filterAdvice: FilterAdvice,
    val insights: List<AdvisorInsight>,
    val chatGptContext: ChatGptAdviceContext
)

data class AdvisorInsight(
    val id: String,
    val type: AdvisorInsightType,
    val severity: AdvisorSeverity,
    val title: String,
    val body: String,
    val evidence: List<String>,
    val recommendedAction: String?
)

enum class AdvisorInsightType {
    PROFITABILITY,
    FILTER,
    ZONE,
    DATA_QUALITY,
    DECISION_BALANCE
}

enum class AdvisorSeverity {
    INFO,
    WATCH,
    ACTION
}

data class FilterAdvice(
    val currentConfig: FilterConfig,
    val recommendedConfig: FilterConfig,
    val recommendations: List<FilterRecommendation>
)

data class FilterRecommendation(
    val filterName: String,
    val currentValue: String,
    val recommendedValue: String,
    val direction: FilterAdjustmentDirection,
    val reason: String,
    val confidence: AdvisorConfidence,
    val sampleSize: Int
)

enum class FilterAdjustmentDirection {
    TIGHTEN,
    RELAX,
    KEEP
}

enum class AdvisorConfidence {
    LOW,
    MEDIUM,
    HIGH
}

data class ChatGptAdviceContext(
    val consentRequired: Boolean,
    val redactionLevel: ChatGptRedactionLevel,
    val modelTask: String,
    val safeSummary: Map<String, String>,
    val recentOfferSignals: List<Map<String, String>>,
    val filterRecommendations: List<Map<String, String>>
)

enum class ChatGptRedactionLevel {
    METRICS_ONLY,
    INCLUDE_ZONE_PREFIXES,
    INCLUDE_RAW_TEXT
}

object TripAdvisorEngine {
    fun advise(
        entries: List<TripHistoryEntry>,
        config: FilterConfig = FilterConfig.default()
    ): TripAdvisorReport {
        val recent = entries.take(MAX_RECENT_OFFERS)
        val analysis = TripHistoryAnalyzer.analyze(recent)
        val filterAdvice = recommendFilters(recent, analysis, config)
        val insights = buildInsights(recent, analysis, filterAdvice, config)

        return TripAdvisorReport(
            analysis = analysis,
            filterAdvice = filterAdvice,
            insights = insights,
            chatGptContext = buildChatGptContext(recent, analysis, filterAdvice)
        )
    }

    fun formatForUi(report: TripAdvisorReport): String {
        val analysis = report.analysis
        if (analysis.totalOffers == 0) {
            return "Ainda nao existe historico capturado.\n\nConselho IA: a preparar contexto seguro quando existirem ofertas."
        }

        val blocked = analysis.suggestedBlockedPrefixes.ifEmpty { listOf("nenhum ainda") }.joinToString(", ")
        val bestZone = analysis.bestPostalPrefix ?: "desconhecido"
        val worstZone = analysis.worstPostalPrefix ?: "desconhecido"
        val insights = report.insights
            .ifEmpty {
                listOf(
                    AdvisorInsight(
                        id = "steady",
                        type = AdvisorInsightType.PROFITABILITY,
                        severity = AdvisorSeverity.INFO,
                        title = "Sem alertas fortes",
                        body = "As ultimas ofertas nao mostram um desvio claro aos filtros atuais.",
                        evidence = emptyList(),
                        recommendedAction = "Manter filtros atuais ate haver mais dados."
                    )
                )
            }
            .joinToString("\n") { insight ->
                val evidence = insight.evidence.take(2).joinToString("; ")
                val suffix = listOfNotNull(evidence.takeIf { it.isNotBlank() }, insight.recommendedAction)
                    .joinToString(" | ")
                "- ${insight.title}: ${insight.body}${suffix.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}"
            }

        val filters = report.filterAdvice.recommendations
            .ifEmpty {
                listOf(
                    FilterRecommendation(
                        filterName = "Filtros",
                        currentValue = "atuais",
                        recommendedValue = "manter",
                        direction = FilterAdjustmentDirection.KEEP,
                        reason = "Sem amostra suficiente para mexer com seguranca.",
                        confidence = AdvisorConfidence.LOW,
                        sampleSize = analysis.totalOffers
                    )
                )
            }
            .joinToString("\n") { recommendation ->
                "- ${recommendation.filterName}: ${recommendation.currentValue} -> ${recommendation.recommendedValue} (${recommendation.reason})"
            }

        return buildString {
            appendLine("Ultimas ${analysis.totalOffers} ofertas")
            appendLine("Aceites: ${analysis.acceptedOffers} | Rejeitadas: ${analysis.rejectedOffers} | Rever: ${analysis.reviewOffers}")
            appendLine("Tarifa media: ${analysis.averageFare.money()}")
            appendLine("Recolha media: ${analysis.averagePickupKm.km()} | Viagem media: ${analysis.averageTripKm.km()}")
            appendLine("Distancia total media: ${analysis.averageTotalKm.km()}")
            appendLine("Media: ${analysis.averageEurPerKm.money("EUR/km")} | ${analysis.averageEurPerHour.money("EUR/h")}")
            appendLine("Melhor codigo postal: $bestZone")
            appendLine("Pior codigo postal: $worstZone")
            appendLine("Sugestao de bloqueio: $blocked")
            appendLine()
            appendLine("Insights:")
            appendLine(insights)
            appendLine()
            appendLine("Filtros recomendados:")
            appendLine(filters)
            appendLine()
            append("Conselho IA: contexto pronto em modo ${report.chatGptContext.redactionLevel.name}; enviar apenas com consentimento.")
        }
    }

    private fun recommendFilters(
        entries: List<TripHistoryEntry>,
        analysis: HistoryAnalysis,
        config: FilterConfig
    ): FilterAdvice {
        var recommended = config
        val recommendations = mutableListOf<FilterRecommendation>()
        val sampleConfidence = confidenceFor(entries.size)

        if (entries.size < MIN_SAMPLE_FOR_FILTER_CHANGE) {
            return FilterAdvice(
                currentConfig = config,
                recommendedConfig = config,
                recommendations = listOf(
                    FilterRecommendation(
                        filterName = "Amostra",
                        currentValue = "${entries.size} ofertas",
                        recommendedValue = "$MIN_SAMPLE_FOR_FILTER_CHANGE+ ofertas",
                        direction = FilterAdjustmentDirection.KEEP,
                        reason = "Guardar mais viagens antes de alterar limites.",
                        confidence = AdvisorConfidence.LOW,
                        sampleSize = entries.size
                    )
                )
            )
        }

        if (analysis.averageEurPerKm > 0.0 && analysis.averageEurPerKm < config.minimumEurPerKm) {
            val target = (config.minimumEurPerKm + 0.10).roundMoney()
            recommended = recommended.copy(minimumEurPerKm = target)
            recommendations += FilterRecommendation(
                filterName = "Minimo EUR/km",
                currentValue = config.minimumEurPerKm.moneyValue(),
                recommendedValue = target.moneyValue(),
                direction = FilterAdjustmentDirection.TIGHTEN,
                reason = "A media recente esta abaixo do limite atual.",
                confidence = sampleConfidence,
                sampleSize = entries.size
            )
        }

        if (analysis.averageEurPerHour > 0.0 && analysis.averageEurPerHour < config.minimumEurPerHour) {
            val target = (config.minimumEurPerHour + 2.0).roundMoney()
            recommended = recommended.copy(minimumEurPerHour = target)
            recommendations += FilterRecommendation(
                filterName = "Minimo EUR/h",
                currentValue = config.minimumEurPerHour.moneyValue(),
                recommendedValue = target.moneyValue(),
                direction = FilterAdjustmentDirection.TIGHTEN,
                reason = "O retorno por hora esta fraco nas ultimas ofertas.",
                confidence = sampleConfidence,
                sampleSize = entries.size
            )
        }

        val farPickupRejected = entries.count { entry ->
            entry.result.decision == OfferDecision.REJECT &&
                entry.offer.pickupDistanceKm?.let { it > config.maximumPickupKm } == true
        }
        if (farPickupRejected >= 2 && analysis.averageEurPerKm < config.minimumEurPerKm * 1.15) {
            val target = (config.maximumPickupKm - 0.5).coerceAtLeast(2.0).roundDistance()
            recommended = recommended.copy(maximumPickupKm = target)
            recommendations += FilterRecommendation(
                filterName = "Maximo recolha",
                currentValue = config.maximumPickupKm.kmValue(),
                recommendedValue = target.kmValue(),
                direction = FilterAdjustmentDirection.TIGHTEN,
                reason = "Recolhas longas estao a aparecer sem compensar bem.",
                confidence = sampleConfidence,
                sampleSize = entries.size
            )
        }

        if (analysis.rejectedOffers > analysis.acceptedOffers * 2 && analysis.averageEurPerKm >= config.minimumEurPerKm * 1.20) {
            val target = (config.maximumPickupKm + 0.5).coerceAtMost(6.0).roundDistance()
            recommended = recommended.copy(maximumPickupKm = target)
            recommendations += FilterRecommendation(
                filterName = "Maximo recolha",
                currentValue = config.maximumPickupKm.kmValue(),
                recommendedValue = target.kmValue(),
                direction = FilterAdjustmentDirection.RELAX,
                reason = "Ha muitas rejeicoes, mas o EUR/km medio esta saudavel.",
                confidence = sampleConfidence,
                sampleSize = entries.size
            )
        }

        val newBlockedPrefixes = analysis.suggestedBlockedPrefixes
            .filterNot { it in config.blockedPostalPrefixes }
        if (newBlockedPrefixes.isNotEmpty()) {
            recommended = recommended.copy(
                blockedPostalPrefixes = config.blockedPostalPrefixes + newBlockedPrefixes
            )
            recommendations += FilterRecommendation(
                filterName = "Zonas bloqueadas",
                currentValue = config.blockedPostalPrefixes.sorted().joinToString(", "),
                recommendedValue = recommended.blockedPostalPrefixes.sorted().joinToString(", "),
                direction = FilterAdjustmentDirection.TIGHTEN,
                reason = "Zonas com pior sinal recente.",
                confidence = sampleConfidence,
                sampleSize = entries.size
            )
        }

        if (recommendations.isEmpty()) {
            recommendations += FilterRecommendation(
                filterName = "Filtros",
                currentValue = "baseline atual",
                recommendedValue = "manter",
                direction = FilterAdjustmentDirection.KEEP,
                reason = "Nao ha sinal forte para ajustar ja.",
                confidence = sampleConfidence,
                sampleSize = entries.size
            )
        }

        return FilterAdvice(config, recommended, recommendations.distinctBy { it.filterName to it.direction })
    }

    private fun buildInsights(
        entries: List<TripHistoryEntry>,
        analysis: HistoryAnalysis,
        filterAdvice: FilterAdvice,
        config: FilterConfig
    ): List<AdvisorInsight> = buildList {
        if (entries.size < MAX_RECENT_OFFERS) {
            add(
                AdvisorInsight(
                    id = "sample-size",
                    type = AdvisorInsightType.DATA_QUALITY,
                    severity = AdvisorSeverity.INFO,
                    title = "Historico ainda curto",
                    body = "As recomendacoes ficam melhores com 10 ofertas recentes.",
                    evidence = listOf("${entries.size}/$MAX_RECENT_OFFERS ofertas disponiveis"),
                    recommendedAction = "Continuar a guardar ofertas antes de automatizar ajustes."
                )
            )
        }

        if (analysis.averageEurPerKm > 0.0 && analysis.averageEurPerKm < config.minimumEurPerKm) {
            add(
                AdvisorInsight(
                    id = "low-eur-km",
                    type = AdvisorInsightType.PROFITABILITY,
                    severity = AdvisorSeverity.ACTION,
                    title = "EUR/km abaixo do alvo",
                    body = "A media recente nao paga bem a distancia total.",
                    evidence = listOf("Media ${analysis.averageEurPerKm.money("EUR/km")}", "Alvo ${config.minimumEurPerKm.money("EUR/km")}"),
                    recommendedAction = "Apertar EUR/km ou recolhas longas."
                )
            )
        }

        if (analysis.averageEurPerHour > 0.0 && analysis.averageEurPerHour < config.minimumEurPerHour) {
            add(
                AdvisorInsight(
                    id = "low-eur-hour",
                    type = AdvisorInsightType.PROFITABILITY,
                    severity = AdvisorSeverity.ACTION,
                    title = "EUR/h abaixo do alvo",
                    body = "O tempo total capturado esta a render abaixo do minimo.",
                    evidence = listOf("Media ${analysis.averageEurPerHour.money("EUR/h")}", "Alvo ${config.minimumEurPerHour.money("EUR/h")}"),
                    recommendedAction = "Evitar viagens longas com tarifa total fraca."
                )
            )
        }

        if (analysis.suggestedBlockedPrefixes.isNotEmpty()) {
            add(
                AdvisorInsight(
                    id = "weak-zones",
                    type = AdvisorInsightType.ZONE,
                    severity = AdvisorSeverity.WATCH,
                    title = "Zonas fracas detectadas",
                    body = "Alguns prefixos postais tiveram sinal economico baixo.",
                    evidence = listOf(analysis.suggestedBlockedPrefixes.joinToString(", ")),
                    recommendedAction = "Testar bloqueio temporario ou exigir tarifa maior nessas zonas."
                )
            )
        }

        if (analysis.rejectedOffers > analysis.acceptedOffers * 2 && analysis.totalOffers >= MIN_SAMPLE_FOR_FILTER_CHANGE) {
            add(
                AdvisorInsight(
                    id = "many-rejections",
                    type = AdvisorInsightType.DECISION_BALANCE,
                    severity = AdvisorSeverity.WATCH,
                    title = "Muitas rejeicoes recentes",
                    body = "Os filtros estao a cortar a maioria das ofertas capturadas.",
                    evidence = listOf("${analysis.rejectedOffers} rejeitadas", "${analysis.acceptedOffers} aceites"),
                    recommendedAction = "Confirmar se a procura esta fraca antes de apertar mais."
                )
            )
        }

        if (filterAdvice.recommendations.any { it.direction != FilterAdjustmentDirection.KEEP }) {
            add(
                AdvisorInsight(
                    id = "filter-change-ready",
                    type = AdvisorInsightType.FILTER,
                    severity = AdvisorSeverity.INFO,
                    title = "Ha ajustes de filtros sugeridos",
                    body = "O advisor preparou uma configuracao recomendada sem alterar a captura.",
                    evidence = filterAdvice.recommendations.map { "${it.filterName}: ${it.recommendedValue}" },
                    recommendedAction = "Aplicar manualmente depois de validar no terreno."
                )
            )
        }
    }

    private fun buildChatGptContext(
        entries: List<TripHistoryEntry>,
        analysis: HistoryAnalysis,
        filterAdvice: FilterAdvice
    ): ChatGptAdviceContext {
        val safeSummary = mapOf(
            "totalOffers" to analysis.totalOffers.toString(),
            "acceptedOffers" to analysis.acceptedOffers.toString(),
            "rejectedOffers" to analysis.rejectedOffers.toString(),
            "reviewOffers" to analysis.reviewOffers.toString(),
            "averageFareEur" to analysis.averageFare.value(),
            "averagePickupKm" to analysis.averagePickupKm.value(),
            "averageTripKm" to analysis.averageTripKm.value(),
            "averageEurPerKm" to analysis.averageEurPerKm.value(),
            "averageEurPerHour" to analysis.averageEurPerHour.value(),
            "bestPostalPrefix" to (analysis.bestPostalPrefix ?: "unknown"),
            "worstPostalPrefix" to (analysis.worstPostalPrefix ?: "unknown")
        )

        val recentOfferSignals = entries.mapIndexed { index, entry ->
            mapOf(
                "rank" to (index + 1).toString(),
                "sourceApp" to entry.offer.sourceApp.name,
                "decision" to entry.result.decision.name,
                "fareEur" to entry.offer.fareEur.valueOrUnknown(),
                "pickupKm" to entry.offer.pickupDistanceKm.valueOrUnknown(),
                "tripKm" to entry.offer.tripDistanceKm.valueOrUnknown(),
                "eurPerKm" to entry.offer.eurPerKm().valueOrUnknown(),
                "eurPerHour" to entry.offer.eurPerHour().valueOrUnknown(),
                "pickupPrefix" to (entry.offer.pickupPostalCode?.take(4) ?: "unknown"),
                "destinationPrefix" to (entry.offer.destinationPostalCode?.take(4) ?: "unknown")
            )
        }

        val filterRecommendations = filterAdvice.recommendations.map { recommendation ->
            mapOf(
                "filterName" to recommendation.filterName,
                "currentValue" to recommendation.currentValue,
                "recommendedValue" to recommendation.recommendedValue,
                "direction" to recommendation.direction.name,
                "confidence" to recommendation.confidence.name,
                "reason" to recommendation.reason
            )
        }

        return ChatGptAdviceContext(
            consentRequired = true,
            redactionLevel = ChatGptRedactionLevel.INCLUDE_ZONE_PREFIXES,
            modelTask = "Give concise driver advice from redacted recent offer metrics and filter recommendations.",
            safeSummary = safeSummary,
            recentOfferSignals = recentOfferSignals,
            filterRecommendations = filterRecommendations
        )
    }

    private fun confidenceFor(sampleSize: Int): AdvisorConfidence =
        when {
            sampleSize >= MAX_RECENT_OFFERS -> AdvisorConfidence.HIGH
            sampleSize >= MIN_SAMPLE_FOR_FILTER_CHANGE -> AdvisorConfidence.MEDIUM
            else -> AdvisorConfidence.LOW
        }

    private fun Double.roundMoney(): Double = ((this * 100.0).roundToInt() / 100.0)

    private fun Double.roundDistance(): Double = ((this * 10.0).roundToInt() / 10.0)

    private fun Double.money(suffix: String = "EUR"): String = "${moneyValue()} $suffix"

    private fun Double.moneyValue(): String = String.format(Locale.US, "%.2f", this)

    private fun Double.km(): String = "${kmValue()} km"

    private fun Double.kmValue(): String = String.format(Locale.US, "%.1f", this)

    private fun Double.value(): String = String.format(Locale.US, "%.2f", this)

    private fun Double?.valueOrUnknown(): String = this?.value() ?: "unknown"

    private const val MAX_RECENT_OFFERS = 10
    private const val MIN_SAMPLE_FOR_FILTER_CHANGE = 5
}
