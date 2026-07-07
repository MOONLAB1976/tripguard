package pt.tripguard.app.rules

import java.text.Normalizer

object TripOfferParser {
    private val uberCategoryMarkers = listOf(
        "uberx priority",
        "uberx and share",
        "uber intercity",
        "uber pet",
        "package",
        "comfort",
        "electric",
        "electic",
        "uberx"
    )

    private val forbiddenAppTexts = listOf(
        "tripguard",
        "oferta ao vivo",
        "ultima oferta lida",
        "historico recente capturado",
        "limpar diagnostico"
    )

    private val homeScreenTexts = listOf(
        "pagina inicial",
        "procurar locais",
        "uber pro blue",
        "recomendado para si",
        "a procurar viagens",
        "folha de itinerario",
        "preferencias",
        "ficar offline",
        "ver o tempo de conducao",
        "radar de viagens",
        "veja os nossos parceiros",
        "bolt rewards",
        "a procura e elevada neste momento",
        "ha bastante movimento a sua volta",
        "ficar online novamente",
        "permanecer offline"
    )

    private val farePatterns = listOf(
        Regex("""(\d+[.,]\d{2})\s*(?:eur|â‚¬|€)""", RegexOption.IGNORE_CASE),
        Regex("""(?:eur|â‚¬|€)\s*(\d+[.,]\d{2})""", RegexOption.IGNORE_CASE)
    )

    private val secondaryFareMarkers = listOf(
        "eur/km",
        "eur/h",
        "por km",
        "por hora",
        "portagem",
        "incluidos pela recolha",
        "incluindo pela recolha",
        "pickup",
        "lucro",
        "bonus",
        "carregamento rapido",
        "km do carregamento rapido",
        "destino extra",
        "fora do alcance"
    )

    private val postalPattern = Regex("""\b(\d{4})-(\d{3})\b""")
    private val shortUberOfferPattern = Regex(
        """(\d+[.,]\d{2})\s*(?:eur|Ã¢â€šÂ¬|â‚¬)\s*,?\s*((?:(\d+)\s*min)\s*,?\s*)?(\d+[.,]?\d*)\s*km\s*(uberx priority|uberx and share|uber intercity|uber pet|uberx|comfort|conforto|electric|electic|package)?""",
        RegexOption.IGNORE_CASE
    )
    private val addressArrowPattern = Regex("""(.+?)\s*(?:->|→)\s*(.+)""")
    private val bracketDurationKmPattern = Regex(
        """((?:(\d+)\s*h\s*)?(\d+)\s*min\.?)\s*\((\d+[.,]?\d*)\s*km\)""",
        RegexOption.IGNORE_CASE
    )
    private val bulletDurationKmPattern = Regex(
        """((?:(\d+)\s*h\s*)?(\d+)\s*min\.?)\s*[•-]\s*(\d+[.,]?\d*)\s*km""",
        RegexOption.IGNORE_CASE
    )
    private val tripPrefixPattern = Regex(
        """viagem\s+de\s+((?:(\d+)\s*h\s*)?(\d+)\s*min\.?)\s*(?:\((\d+[.,]?\d*)\s*km\)|[•-]\s*(\d+[.,]?\d*)\s*km)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(rawText: String, sourceHint: SourceApp? = null): TripOffer? {
        if (rawText.isBlank()) return null

        val normalizedText = rawText.replace('\u00A0', ' ').trim()
        val sourceApp = sourceHint ?: detectSourceApp(normalizedText)
        val candidates = extractCandidateBlocks(normalizedText)

        return candidates
            .asSequence()
            .mapNotNull { parseCandidate(it, sourceApp) }
            .maxByOrNull(::scoreOffer)
    }

    private fun parseCandidate(candidateText: String, sourceHint: SourceApp): TripOffer? {
        if (candidateText.isBlank()) return null

        val matchText = candidateText.normalizeForMatching()
        if (forbiddenAppTexts.any { matchText.contains(it) }) return null

        val homeHits = homeScreenTexts.count { matchText.contains(it) }
        val hasActionMarker = matchText.hasActionMarkers()
        val hasFareMarker = matchText.hasFareMarkers()
        val hasDurationMarkers = matchText.hasDurationMarkers()
        if (homeHits >= 1 && !(hasActionMarker && hasFareMarker && hasDurationMarkers)) return null

        val lines = candidateText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (sourceHint == SourceApp.UBER && !looksLikeUberJourneyCard(lines, matchText)) {
            return null
        }

        val durationBlocks = lines.mapIndexedNotNull { index, line ->
            parseDurationDistance(line)?.copy(lineIndex = index, rawLine = line)
        }

        val sourceApp = if (sourceHint == SourceApp.UNKNOWN) detectSourceApp(candidateText) else sourceHint
        val shortUberOffer = if (sourceApp == SourceApp.UBER) parseShortUberOffer(lines, matchText) else null
        val fare = shortUberOffer?.fare ?: extractFare(lines, candidateText, sourceApp)
        val pickupBlock = selectPickupBlock(sourceApp, durationBlocks)
        val tripBlock = selectTripBlock(sourceApp, durationBlocks, pickupBlock)

        val pickupAddress = shortUberOffer?.pickupAddress ?: pickupBlock?.lineIndex?.let { nextAddressAfter(lines, it, null) }
        val destinationAddress = shortUberOffer?.destinationAddress ?: tripBlock?.lineIndex?.let { nextAddressAfter(lines, it, pickupAddress) }

        if (pickupAddress != null && destinationAddress != null && pickupAddress == destinationAddress) return null

        val pickupPostalCode = pickupAddress?.extractPostalPrefix()
        val destinationPostalCode = destinationAddress?.extractPostalPrefix()

        if (sourceApp == SourceApp.UBER) {
            if (uberCategoryMarkers.none { matchText.contains(it) }) return null
            if (fare == null || fare < 1.0) return null
            if (!hasActionMarker && !matchText.contains("exclusivo")) return null
            val hasClassicUberBlocks = pickupBlockLooksUber(pickupBlock) && tripBlockLooksUber(tripBlock)
            if (!hasClassicUberBlocks && shortUberOffer == null) return null
        }

        if (sourceApp == SourceApp.BOLT) {
            if (!hasActionMarker && !hasFareMarker) return null
        }

        val hasRealOfferSignals = sourceApp != SourceApp.UNKNOWN &&
            fare != null &&
            (pickupBlock != null || shortUberOffer?.pickupDistanceKm != null || shortUberOffer?.tripDistanceKm != null) &&
            (tripBlock != null || shortUberOffer?.tripDistanceKm != null || shortUberOffer?.pickupDistanceKm != null) &&
            pickupAddress != null &&
            destinationAddress != null

        if (!hasRealOfferSignals) return null

        return TripOffer(
            rawText = candidateText,
            sourceApp = sourceApp,
            fareEur = fare,
            pickupDurationMin = pickupBlock?.durationMin ?: shortUberOffer?.pickupDurationMin,
            pickupDistanceKm = pickupBlock?.distanceKm ?: shortUberOffer?.pickupDistanceKm,
            tripDurationMin = tripBlock?.durationMin ?: shortUberOffer?.tripDurationMin,
            tripDistanceKm = tripBlock?.distanceKm ?: shortUberOffer?.tripDistanceKm,
            pickupAddress = pickupAddress,
            destinationAddress = destinationAddress,
            pickupPostalCode = pickupPostalCode,
            destinationPostalCode = destinationPostalCode
        )
    }

    private fun extractCandidateBlocks(rawText: String): List<String> {
        val chunks = rawText
            .split(Regex("""\n\s*\n"""))
            .map { chunk -> sanitizeChunk(chunk) }
            .filter { chunk -> chunk.isNotBlank() }

        val directCandidates = chunks.filter { chunk ->
            val normalized = chunk.normalizeForMatching()
            normalized.hasFareMarkers() ||
                normalized.hasActionMarkers() ||
                normalized.contains("viagem de") ||
                normalized.contains("de distancia") ||
                normalized.contains("liquidos, incluindo impostos")
        }

        val mergedCandidates = directCandidates
            .zipWithNext { first, second -> "$first\n$second" }

        val focusedCandidates = chunks.flatMap { chunk ->
            extractFocusedCandidates(chunk)
        }

        return (directCandidates + mergedCandidates + focusedCandidates)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractFocusedCandidates(chunk: String): List<String> {
        val lines = chunk.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val candidates = mutableListOf<String>()
        lines.indices.forEach { index ->
            val line = lines[index].normalizeForMatching()
            val looksRelevant =
                line.contains("apos deducao de taxa de servico") ||
                    line.contains("aceitar") ||
                    line.contains("corresponder") ||
                    line.contains("oportunidade") ||
                    line.contains("viagem de") ||
                    line.contains("de distancia") ||
                    line.contains("->") ||
                    line.contains("→") ||
                    uberCategoryMarkers.any { marker -> line.contains(marker) }
            if (!looksRelevant) return@forEach

            val start = (index - 3).coerceAtLeast(0)
            val end = (index + 10).coerceAtMost(lines.lastIndex)
            val snippet = lines.subList(start, end + 1).joinToString("\n").trim()
            val normalizedSnippet = snippet.normalizeForMatching()
            if (
                (
                    normalizedSnippet.hasFareMarkers() &&
                        normalizedSnippet.contains("viagem de") &&
                        normalizedSnippet.contains("de distancia") &&
                        uberCategoryMarkers.any { marker -> normalizedSnippet.contains(marker) }
                    ) ||
                    looksLikeShortUberSnippet(normalizedSnippet)
            ) {
                candidates += snippet
            }
        }
        return candidates.distinct()
    }

    private fun sanitizeChunk(chunk: String): String {
        val cleanedLines = chunk.lines()
            .map { it.trim() }
            .dropWhile { line ->
                line.startsWith("WINDOW#") ||
                    line.startsWith("EVENT_SOURCE") ||
                    line.startsWith("EVENT_TEXT") ||
                    line.startsWith("UBER_FALLBACK_WINDOW#") ||
                    line.startsWith("pkg=") ||
                    line.startsWith("WINDOW #")
            }
            .filter { it.isNotBlank() }
        return cleanedLines.joinToString("\n").trim()
    }

    private fun parseDurationDistance(line: String): DurationDistanceBlock? {
        if (line.looksLikeHelperMetricLine()) return null
        val tripMatch = tripPrefixPattern.find(line)
        if (tripMatch != null) {
            val hours = tripMatch.groupValues.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            val minutes = tripMatch.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            val distanceKm = listOf(
                tripMatch.groupValues.getOrNull(4),
                tripMatch.groupValues.getOrNull(5)
            ).firstOrNull { !it.isNullOrBlank() }
                ?.normalizeDecimal()
                ?.toDoubleOrNull()
            return DurationDistanceBlock(
                durationMin = hours * 60.0 + minutes,
                distanceKm = distanceKm,
                lineIndex = -1,
                rawLine = line
            )
        }

        val match = bracketDurationKmPattern.find(line) ?: bulletDurationKmPattern.find(line) ?: return null
        val hours = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        val minutes = match.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
        val distanceKm = match.groupValues.getOrNull(4)?.normalizeDecimal()?.toDoubleOrNull()
        return DurationDistanceBlock(
            durationMin = hours * 60.0 + minutes,
            distanceKm = distanceKm,
            lineIndex = -1,
            rawLine = line
        )
    }

    private fun nextAddressAfter(lines: List<String>, startIndex: Int, previousAddress: String?): String? {
        val buffer = mutableListOf<String>()
        for (index in (startIndex + 1) until lines.size) {
            val candidate = lines[index]
            val normalized = candidate.normalizeForMatching()
            if (candidate == previousAddress) continue
            if (normalized.startsWith("window#")) continue
            if (normalized == "event_source") continue
            if (normalized.contains("fare below minimum")) break
            if (normalized.contains("low yield")) break
            if (normalized.contains("low hourly")) break
            if (normalized.contains("blocked postal")) break
            if (normalized.contains("uber pro blue")) return null
            if (normalized.contains("preferencias")) return null
            if (normalized.contains("pagina inicial")) return null
            if (normalized.contains("recolha") && !candidate.containsPostalOrStreetShape()) continue
            if (normalized.contains("destino") && !candidate.containsPostalOrStreetShape()) continue
            if (normalized.contains("eur/km") || normalized.contains("eur/h")) break
            if (normalized.contains("apos deducao de taxa de servico")) continue
            if (normalized.contains("destino extra")) continue
            if (normalized.contains("mais de 30 min")) continue
            if ("aceitar" in normalized || "recusar" in normalized || "corresponder" in normalized) break
            if (parseDurationDistance(candidate) != null) break

            if (candidate.containsPostalOrStreetShape()) {
                buffer += candidate
                val nextLine = lines.getOrNull(index + 1)
                if (nextLine != null && nextLine.containsPostalOrStreetShape() && nextLine != previousAddress) {
                    buffer += nextLine
                }
                return buffer.joinToString(", ")
            }
        }
        return null
    }

    private fun parseShortUberOffer(lines: List<String>, matchText: String): ShortUberOffer? {
        if (!looksLikeShortUberSnippet(matchText)) return null

        val metricLine = lines.firstOrNull { line ->
            shortUberOfferPattern.containsMatchIn(line.normalizeForMatching())
        } ?: return null

        val metricMatch = shortUberOfferPattern.find(metricLine.normalizeForMatching()) ?: return null
        val fare = metricMatch.groupValues.getOrNull(1)?.normalizeDecimal()?.toDoubleOrNull() ?: return null
        val durationMin = metricMatch.groupValues.getOrNull(3)?.toDoubleOrNull()
        val distanceKm = metricMatch.groupValues.getOrNull(4)?.normalizeDecimal()?.toDoubleOrNull() ?: return null

        val arrowPair = lines.firstNotNullOfOrNull(::parseArrowAddresses)
        val pickupAddress = arrowPair?.first
        val destinationAddress = arrowPair?.second

        return ShortUberOffer(
            fare = fare,
            pickupDurationMin = null,
            pickupDistanceKm = null,
            tripDurationMin = durationMin,
            tripDistanceKm = distanceKm,
            pickupAddress = pickupAddress,
            destinationAddress = destinationAddress
        )
    }

    private fun selectPickupBlock(
        sourceApp: SourceApp,
        durationBlocks: List<DurationDistanceBlock>
    ): DurationDistanceBlock? {
        if (sourceApp == SourceApp.UBER) {
            return durationBlocks.firstOrNull { block ->
                val normalized = block.rawLine.normalizeForMatching()
                normalized.contains("de distancia") && !normalized.contains("total")
            }
        }

        return durationBlocks.firstOrNull { block ->
            val normalized = block.rawLine.normalizeForMatching()
            normalized.contains("pickup") && !normalized.contains("total")
        } ?: durationBlocks.firstOrNull { block ->
            !block.rawLine.looksLikeHelperMetricLine()
        }
    }

    private fun selectTripBlock(
        sourceApp: SourceApp,
        durationBlocks: List<DurationDistanceBlock>,
        pickupBlock: DurationDistanceBlock?
    ): DurationDistanceBlock? {
        val explicitTrip = durationBlocks.firstOrNull { block ->
            block !== pickupBlock && block.rawLine.normalizeForMatching().contains("viagem de")
        }
        if (explicitTrip != null) return explicitTrip

        if (sourceApp == SourceApp.UBER) {
            return null
        }

        return durationBlocks.firstOrNull { block ->
            block !== pickupBlock && !block.rawLine.looksLikeHelperMetricLine()
        }
    }

    private fun parseArrowAddresses(line: String): Pair<String, String>? {
        val match = addressArrowPattern.find(line) ?: return null
        val pickup = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val destination = match.groupValues.getOrNull(2)?.trim().orEmpty()
        if (!pickup.containsPostalOrStreetShape() || !destination.containsPostalOrStreetShape()) return null
        return pickup to destination
    }

    private fun looksLikeShortUberSnippet(normalizedText: String): Boolean {
        val hasFare = farePatterns.any { it.containsMatchIn(normalizedText) }
        val hasCategory = uberCategoryMarkers.any { normalizedText.contains(it) }
        val hasDistance = Regex("""\d+[.,]?\d*\s*km""", RegexOption.IGNORE_CASE).containsMatchIn(normalizedText)
        val hasArrow = normalizedText.contains("->") || normalizedText.contains("→")
        return hasFare && hasCategory && (hasArrow || hasDistance)
    }

    private fun detectSourceApp(text: String): SourceApp {
        val lower = text.normalizeForMatching()
        return when {
            "bolt" in lower || "liquidos, incluindo impostos" in lower || "fora do alcance" in lower -> SourceApp.BOLT
            "uber" in lower ||
                "uberx" in lower ||
                "comfort" in lower ||
                "electic" in lower ||
                "electric" in lower ||
                "conforto" in lower ||
                "exclusivo" in lower ||
                "apos deducao de taxa de servico" in lower ||
                "corresponder" in lower ||
                "destino extra" in lower -> SourceApp.UBER
            else -> SourceApp.UNKNOWN
        }
    }

    private fun looksLikeUberJourneyCard(lines: List<String>, matchText: String): Boolean {
        if (looksLikeShortUberSnippet(matchText)) return true
        val lineSignals = lines.count { line ->
            val normalized = line.normalizeForMatching()
            normalized.contains("apos deducao de taxa de servico") ||
                normalized.contains("liquidos, incluindo impostos") ||
                normalized.contains("de distancia") ||
                normalized.contains("viagem de") ||
                normalized.contains("aceitar") ||
                normalized.contains("corresponder") ||
                normalized.contains("destino extra") ||
                normalized.contains("incluidos pela recolha") ||
                normalized.contains("incluindo pela recolha") ||
                normalized.contains("mais de 30 min")
        }
        val hasCategory = uberCategoryMarkers.any { matchText.contains(it) }
        val hasMoney = farePatterns.any { it.containsMatchIn(lines.joinToString("\n")) }
        val durationBlocks = lines.mapNotNull(::parseDurationDistance)
        val hasPickupAndTripShape = durationBlocks.size >= 2
        return hasCategory &&
            hasMoney &&
            (
                lineSignals >= 2 ||
                    (hasPickupAndTripShape && matchText.hasActionMarkers()) ||
                    (hasPickupAndTripShape && matchText.contains("exclusivo"))
                )
    }

    private fun pickupBlockLooksUber(block: DurationDistanceBlock?): Boolean {
        if (block == null) return false
        val normalized = block.rawLine.normalizeForMatching()
        return normalized.contains("de distancia") ||
            normalized.contains("pickup") ||
            normalized.contains("incluidos pela recolha") ||
            normalized.contains("incluindo pela recolha") ||
            normalized.matches(Regex(""".*\d+\s*min.*\d+[.,]?\d*\s*km.*"""))
    }

    private fun tripBlockLooksUber(block: DurationDistanceBlock?): Boolean {
        if (block == null) return false
        val normalized = block.rawLine.normalizeForMatching()
        return normalized.contains("viagem de") ||
            normalized.contains("destino extra") ||
            normalized.contains("mais de 30 min") ||
            normalized.matches(Regex(""".*\d+\s*min.*\d+[.,]?\d*\s*km.*"""))
    }

    private fun scoreOffer(offer: TripOffer): Int {
        var score = 0
        if (offer.fareEur != null) score += 5
        if (offer.pickupDistanceKm != null) score += 4
        if (offer.tripDistanceKm != null) score += 4
        if (offer.pickupAddress != null) score += 3
        if (offer.destinationAddress != null) score += 3
        if (offer.pickupPostalCode != null) score += 2
        if (offer.destinationPostalCode != null) score += 2
        if (offer.sourceApp != SourceApp.UNKNOWN) score += 2
        return score
    }

    private fun extractFare(lines: List<String>, candidateText: String, sourceApp: SourceApp): Double? {
        val allCandidates = lines.flatMapIndexed { index, line ->
            extractMoneyCandidates(line, index)
        }
        if (allCandidates.isEmpty()) {
            return farePatterns
                .firstNotNullOfOrNull { it.find(candidateText)?.groupValues?.getOrNull(1) }
                ?.normalizeDecimal()
                ?.toDoubleOrNull()
        }

        val fareMarkerIndex = lines.indexOfFirst { it.normalizeForMatching().hasFareMarkers() }
        val prioritizedCandidates = if (fareMarkerIndex >= 0) {
            val windowStart = (fareMarkerIndex - 3).coerceAtLeast(0)
            val windowEnd = (fareMarkerIndex + 1).coerceAtMost(lines.lastIndex)
            allCandidates.filter { it.lineIndex in windowStart..windowEnd }
        } else {
            allCandidates
        }

        val bestCandidate = prioritizedCandidates
            .filterNot { it.isSecondary }
            .filterNot { sourceApp == SourceApp.BOLT && it.normalizedLine.contains("portagem") }
            .maxByOrNull { fareScore(it, sourceApp, fareMarkerIndex) }
            ?: allCandidates
                .filterNot { it.isSecondary }
                .filterNot { sourceApp == SourceApp.BOLT && it.normalizedLine.contains("portagem") }
                .maxByOrNull { fareScore(it, sourceApp, fareMarkerIndex) }
            ?: prioritizedCandidates.maxByOrNull { fareScore(it, sourceApp, fareMarkerIndex) }

        return bestCandidate?.amount
    }

    private fun extractMoneyCandidates(line: String, lineIndex: Int): List<MoneyCandidate> {
        val normalizedLine = line.normalizeForMatching()
        return farePatterns.flatMap { pattern ->
            pattern.findAll(line).mapNotNull { match ->
                val amount = match.groupValues
                    .getOrNull(1)
                    ?.normalizeDecimal()
                    ?.toDoubleOrNull()
                    ?: return@mapNotNull null
                MoneyCandidate(
                    amount = amount,
                    lineIndex = lineIndex,
                    normalizedLine = normalizedLine,
                    isSecondary = secondaryFareMarkers.any { marker -> normalizedLine.contains(marker) } ||
                        normalizedLine.contains("%") ||
                        normalizedLine.startsWith("+")
                )
            }.toList()
        }
    }

    private fun fareScore(candidate: MoneyCandidate, sourceApp: SourceApp, fareMarkerIndex: Int): Int {
        var score = 0
        if (!candidate.isSecondary) score += 40 else score -= 30
        if (candidate.amount < 1.0) score -= 50
        if (candidate.amount >= 2.0) score += 15
        if (candidate.amount >= 4.0) score += 8
        if (candidate.amount >= 6.0) score += 4
        if (fareMarkerIndex >= 0) {
            score += 20 - kotlin.math.abs(candidate.lineIndex - fareMarkerIndex).coerceAtMost(20)
        } else {
            score += 12 - candidate.lineIndex.coerceAtMost(12)
        }
        if (sourceApp == SourceApp.BOLT && candidate.normalizedLine.contains("liquidos, incluindo impostos")) score += 12
        if (sourceApp == SourceApp.UBER && candidate.normalizedLine.contains("apos deducao de taxa de servico")) score += 12
        return score
    }

    private fun String.extractPostalPrefix(): String? =
        postalPattern.find(this)?.groupValues?.getOrNull(1)

    private fun String.containsPostalOrStreetShape(): Boolean {
        val lower = normalizeForMatching()
        return extractPostalPrefix() != null ||
            listOf(
                "rua",
                "r.",
                "avenida",
                "av.",
                "travessa",
                "largo",
                "praca",
                "porto",
                "matosinhos",
                "gaia",
                "maia",
                "valongo",
                "ermesinde",
                "lavra",
                "custoias",
                "perafita",
                "gondomar"
            ).any { it in lower }
    }

    private fun String.hasActionMarkers(): Boolean =
        listOf("aceitar", "corresponder", "recusar").any { contains(it) }

    private fun String.hasFareMarkers(): Boolean =
        contains("apos deducao de taxa de servico") ||
            contains("liquidos, incluindo impostos")

    private fun String.hasDurationMarkers(): Boolean =
        contains("viagem de") || contains("de distancia")

    private fun String.looksLikeHelperMetricLine(): Boolean {
        val normalized = normalizeForMatching()
        if (normalized.contains("eur/km") || normalized.contains("eur/h")) return true
        if (normalized.contains("lucro") || normalized.contains("pickup")) return true
        if (normalized.contains("total") && normalized.contains("km")) return true
        if (normalized.contains("recolha") || normalized.contains("destino")) return true
        if (Regex("""\d+[.,]?\d*\s*km\s*[:|]\s*\d+\s*min""").containsMatchIn(normalized)) return true
        if (Regex("""\d+\s*min\s*[:|]\s*\d+[.,]?\d*\s*km""").containsMatchIn(normalized)) return true
        return false
    }

    private fun String.normalizeDecimal(): String = replace(",", ".")

    private fun String.normalizeForMatching(): String {
        val ascii = Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return ascii.lowercase()
    }

    private data class DurationDistanceBlock(
        val durationMin: Double,
        val distanceKm: Double?,
        val lineIndex: Int,
        val rawLine: String
    )

    private data class MoneyCandidate(
        val amount: Double,
        val lineIndex: Int,
        val normalizedLine: String,
        val isSecondary: Boolean
    )

    private data class ShortUberOffer(
        val fare: Double,
        val pickupDurationMin: Double?,
        val pickupDistanceKm: Double?,
        val tripDurationMin: Double?,
        val tripDistanceKm: Double?,
        val pickupAddress: String?,
        val destinationAddress: String?
    )
}
