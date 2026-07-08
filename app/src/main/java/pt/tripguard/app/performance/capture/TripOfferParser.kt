package pt.tripguard.app.performance.capture

import pt.tripguard.app.core.domain.SourceApp
import pt.tripguard.app.core.domain.TripOffer

object TripOfferParser {
    private val moneyPattern = Regex("""(\d+(?:[.,]\d{2})?)\s*(?:EUR|\u20AC|€)""", RegexOption.IGNORE_CASE)
    private val kmPattern = Regex("""(\d+[.,]?\d*)\s*km""", RegexOption.IGNORE_CASE)
    private val durationDistancePattern = Regex(
        """((?:(\d+)\s*h\s*)?(\d+)\s*min)\s*[\u2022\u00B7|.-]\s*(\d+[.,]?\d*)\s*km""",
        RegexOption.IGNORE_CASE
    )
    private val durationPattern = Regex("""(?:(\d+)\s*h\s*)?(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val uberPickupPattern = Regex(
        """(?i)((?:(\d+)\s*h\s*)?(\d+)\s*min)\s*\((\d+[.,]?\d*)\s*km\)\s*de\s*dist""",
    )
    private val uberTripPattern = Regex(
        """(?i)viagem\s+de\s+((?:(\d+)\s*h\s*)?(\d+)\s*min)\s*\((\d+[.,]?\d*)\s*km\)""",
    )
    private val uberOpportunityPattern = Regex(
        """(?i)(\d+[.,]\d{2})\s*(?:EUR|\u20AC)\s*,\s*(\d+[.,]?\d*)\s*km\s+([A-Za-z][A-Za-z0-9 +_-]{1,40})"""
    )
    private val routeArrowPattern = Regex("""(.+?)\s*(?:\u2192|->)\s*(.+)""")
    private val strictPostalPattern = Regex("""\b(\d{4})-\d{3}\b""")
    private val streetHints = listOf(
        "rua", "avenida", "av.", "travessa", "largo", "praca", "praça", "rotunda",
        "estrada", "r.", "av ", "via ", "praceta"
    )
    private val boltBlockedFareHints = listOf(
        "portagem", "toll", "lucro", "km", "hora", "%", "pickup",
        // Bloquear linhas com métricas do widget PEGGA PT / Mystro
        "€/km", "€/h", "eur/km", "eur/h", "propostas", "turno", "hoje", "médias", "medias"
    )
    private val uberBlockedFareHints = listOf(
        "incluidos pela recolha", "incluídos pela recolha", "pickup", "km", "hora", "%"
    )
    private val pickupHints = listOf("pickup", "recolha", "pick up", "apanhar", "distancia")
    private val destinationHints = listOf("destination", "dropoff", "destino", "chegada", "drop off")

    fun parse(rawText: String, sourceHint: SourceApp = SourceApp.UNKNOWN): TripOffer? =
        inspect(rawText, sourceHint).offer

    fun inspect(rawText: String, sourceHint: SourceApp = SourceApp.UNKNOWN): ParseInspection {
        if (rawText.isBlank()) {
            return ParseInspection(
                offer = null,
                sourceApp = sourceHint,
                notes = listOf("raw-text-empty")
            )
        }

        val cleanedText = cleanOverlayText(rawText)
        val normalizedText = normalizeRawText(cleanedText)
        val sourceApp = detectSourceApp(normalizedText, sourceHint)

        if (isTripActiveOrNavigation(normalizedText)) {
            return ParseInspection(
                offer = null,
                sourceApp = sourceApp,
                notes = listOf("trip-active-or-navigation-skipped")
            )
        }

        val lines = normalizedText
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val notes = mutableListOf(
            "source-hint=${sourceHint.name}",
            "source-detected=${sourceApp.name}",
            "lines=${lines.size}",
            "chars=${normalizedText.length}"
        )
        val parsed = when (sourceApp) {
            SourceApp.UBER -> parseUber(normalizedText, lines, notes)
            SourceApp.BOLT -> parseBolt(normalizedText, lines, notes)
            SourceApp.UNKNOWN -> parseGeneric(normalizedText, lines, sourceApp, notes)
        }

        if (parsed == null) {
            notes += "parsed-null"
            return ParseInspection(
                offer = null,
                sourceApp = sourceApp,
                notes = notes
            )
        }

        val hasDistance = parsed.pickupDistanceKm != null || parsed.tripDistanceKm != null
        val hasKnownSource = parsed.sourceApp != SourceApp.UNKNOWN
        val hasAddressSignal = parsed.pickupPostalCode != null ||
            parsed.destinationPostalCode != null ||
            parsed.pickupAddress != null ||
            parsed.destinationAddress != null

        if (!hasDistance || (!hasKnownSource && !hasAddressSignal)) {
            if (!hasDistance) notes += "rejected=no-distances"
            if (!hasKnownSource && !hasAddressSignal) notes += "rejected=unknown-source-and-no-address"
            return ParseInspection(
                offer = null,
                sourceApp = sourceApp,
                notes = notes
            )
        }

        notes += listOf(
            "fare=${parsed.fareEur?.format2() ?: "?"}",
            "pickup-km=${parsed.pickupDistanceKm?.format1() ?: "?"}",
            "trip-km=${parsed.tripDistanceKm?.format1() ?: "?"}",
            "pickup-min=${parsed.pickupDurationMin?.format1() ?: "?"}",
            "trip-min=${parsed.tripDurationMin?.format1() ?: "?"}",
            "pickup-zone=${parsed.pickupPostalCode ?: "?"}",
            "dest-zone=${parsed.destinationPostalCode ?: "?"}"
        )
        return ParseInspection(
            offer = parsed,
            sourceApp = sourceApp,
            notes = notes
        )
    }

    private fun parseUber(text: String, lines: List<String>, notes: MutableList<String>): TripOffer? {
        val lowerText = text.lowercase()
        val hasIgnoreKeywords = lowerText.contains("página inicial") ||
                lowerText.contains("pagina inicial") ||
                lowerText.contains("está online") ||
                lowerText.contains("esta online")

        if (hasIgnoreKeywords) {
            val hasDistances = kmPattern.containsMatchIn(text)
            if (!hasDistances) {
                notes += "uber-ignored=initial-screen-no-distances"
                return null
            }
        }

        val fare = selectFare(lines, uberBlockedFareHints)

        val pickupMatch = uberPickupPattern.find(text)
        val tripMatch = uberTripPattern.find(text)

        var pickupDurationMin: Double? = null
        var pickupDistanceKm: Double? = null
        if (pickupMatch != null) {
            pickupDurationMin = pickupMatch.toDurationMinutes(2, 3)
            pickupDistanceKm = pickupMatch.groupValues.getOrNull(4)?.normalizeDecimal()?.toDoubleOrNull()
        }

        var tripDurationMin: Double? = null
        var tripDistanceKm: Double? = null
        if (tripMatch != null) {
            tripDurationMin = tripMatch.toDurationMinutes(2, 3)
            tripDistanceKm = tripMatch.groupValues.getOrNull(4)?.normalizeDecimal()?.toDoubleOrNull()
        }

        val durations = durationPattern.findAll(text)
            .map { match -> match.toDurationMinutes(1, 2) }
            .toList()

        val distances = kmPattern.findAll(text)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.normalizeDecimal()?.toDoubleOrNull() }
            .toList()

        if (pickupDurationMin == null) pickupDurationMin = durations.getOrNull(0)
        if (pickupDistanceKm == null) pickupDistanceKm = distances.getOrNull(0)
        if (tripDurationMin == null) tripDurationMin = durations.getOrNull(1)
        if (tripDistanceKm == null) tripDistanceKm = distances.getOrNull(1)

        notes += "uber-durations-size=${durations.size}"
        notes += "uber-distances-size=${distances.size}"
        notes += "uber-fare=${fare?.format2() ?: "?"}"

        val pickupAddress = extractUberPickupAddress(lines) ?: lines.firstOrNull { it.containsPostalOrStreetShape() }?.cleanAddressLine()
        val destinationAddress = extractUberDestinationAddress(lines) ?: lines.filter { it.containsPostalOrStreetShape() && it.cleanAddressLine() != pickupAddress }.firstOrNull()?.cleanAddressLine()

        notes += "uber-pickup-address=${pickupAddress != null}"
        notes += "uber-destination-address=${destinationAddress != null}"

        if (fare != null && (pickupDistanceKm != null || tripDistanceKm != null)) {
            return TripOffer(
                rawText = text,
                sourceApp = SourceApp.UBER,
                fareEur = fare,
                pickupDurationMin = pickupDurationMin,
                pickupDistanceKm = pickupDistanceKm,
                tripDurationMin = tripDurationMin,
                tripDistanceKm = tripDistanceKm,
                pickupAddress = pickupAddress,
                destinationAddress = destinationAddress,
                pickupPostalCode = pickupAddress.extractPostalPrefix(),
                destinationPostalCode = destinationAddress.extractPostalPrefix()
            )
        }

        val opportunityMatch = uberOpportunityPattern.find(text)
        val opportunityRoute = lines.firstNotNullOfOrNull { line ->
            routeArrowPattern.matchEntire(line)
        }
        notes += "uber-opportunity-match=${opportunityMatch != null}"
        notes += "uber-route-arrow=${opportunityRoute != null}"

        if ((opportunityMatch != null || opportunityRoute != null) && (fare != null || opportunityMatch?.groupValues?.getOrNull(1)?.normalizeDecimal()?.toDoubleOrNull() != null)) {
            val opportunityFare = fare
                ?: opportunityMatch?.groupValues?.getOrNull(1)?.normalizeDecimal()?.toDoubleOrNull()
            val ambiguousDistance = opportunityMatch?.groupValues?.getOrNull(2)?.normalizeDecimal()?.toDoubleOrNull()
            val opportunityPickupAddress = opportunityRoute?.groupValues?.getOrNull(1)?.cleanAddressLine()
            val opportunityDestinationAddress = opportunityRoute?.groupValues?.getOrNull(2)?.cleanAddressLine()

            return TripOffer(
                rawText = text,
                sourceApp = SourceApp.UBER,
                fareEur = opportunityFare,
                pickupDurationMin = null,
                pickupDistanceKm = null,
                tripDurationMin = null,
                tripDistanceKm = ambiguousDistance,
                pickupAddress = opportunityPickupAddress ?: pickupAddress,
                destinationAddress = opportunityDestinationAddress ?: destinationAddress,
                pickupPostalCode = (opportunityPickupAddress ?: pickupAddress).extractPostalPrefix(),
                destinationPostalCode = (opportunityDestinationAddress ?: destinationAddress).extractPostalPrefix()
            )
        }

        return null
    }

    private fun parseBolt(text: String, lines: List<String>, notes: MutableList<String>): TripOffer? {
        val fare = selectFare(lines, boltBlockedFareHints)
        val routeBlocks = durationDistancePattern.findAll(text).map { match ->
            DurationDistanceBlock(
                durationMin = match.toDurationMinutes(2, 3),
                distanceKm = match.groupValues.getOrNull(4)?.normalizeDecimal()?.toDoubleOrNull()
            )
        }.toList()
        notes += "bolt-route-blocks=${routeBlocks.size}"
        notes += "bolt-fare=${fare?.format2() ?: "?"}"

        val pickupAddress = extractAddressAfterLine(lines, pickupHints)
        val destinationAddress = extractAddressAfterLine(lines, destinationHints, skipFirstStreet = true)
        notes += "bolt-pickup-address=${pickupAddress != null}"
        notes += "bolt-destination-address=${destinationAddress != null}"

        val hasDistanceOrDuration = routeBlocks.isNotEmpty()
        val hasAddress = pickupAddress != null || destinationAddress != null

        // Uma tarifa isolada (sem distâncias, tempos ou endereço) é falso positivo
        // — pode vir do widget PEGGA PT ou outro widget do launcher
        if (fare != null && !hasDistanceOrDuration && !hasAddress) {
            notes += "bolt-rejected=bare-fare-no-route"
            return null
        }

        return TripOffer(
            rawText = text,
            sourceApp = SourceApp.BOLT,
            fareEur = fare,
            pickupDurationMin = routeBlocks.firstOrNull()?.durationMin,
            pickupDistanceKm = routeBlocks.firstOrNull()?.distanceKm,
            tripDurationMin = routeBlocks.getOrNull(1)?.durationMin,
            tripDistanceKm = routeBlocks.getOrNull(1)?.distanceKm,
            pickupAddress = pickupAddress,
            destinationAddress = destinationAddress,
            pickupPostalCode = pickupAddress.extractPostalPrefix(),
            destinationPostalCode = destinationAddress.extractPostalPrefix()
        ).takeIf {
            fare != null || routeBlocks.isNotEmpty() || pickupAddress != null || destinationAddress != null
        }
    }

    private fun parseGeneric(
        text: String,
        lines: List<String>,
        sourceApp: SourceApp,
        notes: MutableList<String>
    ): TripOffer? {
        val fare = selectFare(lines, emptyList()) ?: moneyPattern.find(text)?.groupValues?.getOrNull(1)
            ?.normalizeDecimal()?.toDoubleOrNull()

        val durationDistanceBlocks = durationDistancePattern.findAll(text).map { match ->
            DurationDistanceBlock(
                durationMin = match.toDurationMinutes(2, 3),
                distanceKm = match.groupValues.getOrNull(4)?.normalizeDecimal()?.toDoubleOrNull()
            )
        }.toList()

        val durationValues = durationPattern.findAll(text)
            .map { match -> match.toDurationMinutes(1, 2) }
            .toList()

        val allKmValues = kmPattern.findAll(text)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.normalizeDecimal()?.toDoubleOrNull() }
            .toList()

        val pickupAddress = lines.firstOrNull { it.containsPostalOrStreetShape() }
        val destinationAddress = lines.dropWhile { it != pickupAddress }
            .drop(1)
            .firstOrNull { it.containsPostalOrStreetShape() }
        notes += "generic-blocks=${durationDistanceBlocks.size}"
        notes += "generic-fare=${fare?.format2() ?: "?"}"
        notes += "generic-pickup-address=${pickupAddress != null}"
        notes += "generic-destination-address=${destinationAddress != null}"

        return TripOffer(
            rawText = text,
            sourceApp = sourceApp,
            fareEur = fare,
            pickupDurationMin = durationDistanceBlocks.firstOrNull()?.durationMin ?: durationValues.firstOrNull(),
            pickupDistanceKm = durationDistanceBlocks.firstOrNull()?.distanceKm ?: allKmValues.firstOrNull(),
            tripDurationMin = durationDistanceBlocks.getOrNull(1)?.durationMin ?: durationValues.getOrNull(1),
            tripDistanceKm = durationDistanceBlocks.getOrNull(1)?.distanceKm ?: allKmValues.getOrNull(1),
            pickupAddress = pickupAddress,
            destinationAddress = destinationAddress,
            pickupPostalCode = pickupAddress.extractPostalPrefix(),
            destinationPostalCode = destinationAddress.extractPostalPrefix()
        )
    }

    private fun selectFare(lines: List<String>, blockedHints: List<String>): Double? {
        val preferred = lines.firstNotNullOfOrNull { line ->
            if (blockedHints.any { hint -> line.contains(hint, ignoreCase = true) }) {
                return@firstNotNullOfOrNull null
            }
            if (line.contains("->") || line.contains("\u2192")) return@firstNotNullOfOrNull null
            if (line.contains("min", ignoreCase = true) && line.contains("km", ignoreCase = true)) {
                return@firstNotNullOfOrNull null
            }
            moneyPattern.find(line)?.groupValues?.getOrNull(1)?.normalizeDecimal()?.toDoubleOrNull()
        }

        if (preferred != null) return preferred

        return lines.asSequence()
            .filterNot { line -> blockedHints.any { hint -> line.contains(hint, ignoreCase = true) } }
            .flatMap { line -> moneyPattern.findAll(line).map { it.groupValues[1] } }
            .mapNotNull { it.normalizeDecimal().toDoubleOrNull() }
            .firstOrNull()
    }

    private fun extractUberPickupAddress(lines: List<String>): String? {
        val pickupIndex = lines.indexOfFirst {
            uberPickupPattern.containsMatchIn(it) ||
                (it.contains("distancia", ignoreCase = true) && it.contains("km", ignoreCase = true))
        }
        if (pickupIndex < 0) return null

        return lines.drop(pickupIndex + 1)
            .takeWhile { !it.contains("viagem de", ignoreCase = true) && !it.contains("carregamento", ignoreCase = true) }
            .firstOrNull { it.containsPostalOrStreetShape() }
            ?.cleanAddressLine()
    }

    private fun extractUberDestinationAddress(lines: List<String>): String? {
        val tripIndex = lines.indexOfFirst { it.contains("viagem de", ignoreCase = true) }
        if (tripIndex < 0) {
            return lines.firstNotNullOfOrNull { line ->
                routeArrowPattern.matchEntire(line)?.groupValues?.getOrNull(2)?.cleanAddressLine()
            }
        }

        return lines.drop(tripIndex + 1)
            .takeWhile { !it.contains("carregamento", ignoreCase = true) && !it.contains("aceitar", ignoreCase = true) && !it.contains("corresponder", ignoreCase = true) }
            .firstOrNull { it.containsPostalOrStreetShape() }
            ?.cleanAddressLine()
    }

    private fun extractAddressAfterLine(
        lines: List<String>,
        hints: List<String>,
        skipFirstStreet: Boolean = false
    ): String? {
        val startIndex = lines.indexOfFirst { line ->
            hints.any { hint -> line.contains(hint, ignoreCase = true) }
        }
        if (startIndex < 0) return null

        val streetLines = lines.drop(startIndex + 1)
            .takeWhile { !it.contains("aceitar", ignoreCase = true) && !it.contains("corresponder", ignoreCase = true) }
            .filter { it.containsPostalOrStreetShape() }

        return if (skipFirstStreet) streetLines.drop(1).firstOrNull()?.cleanAddressLine()
        else streetLines.firstOrNull()?.cleanAddressLine()
    }

    private fun detectSourceApp(text: String, sourceHint: SourceApp): SourceApp {
        if (sourceHint != SourceApp.UNKNOWN) return sourceHint

        val lower = text.lowercase()
        return when {
            "bolt" in lower || "mtakso" in lower || "green" in lower -> SourceApp.BOLT
            "uber" in lower ||
                "uberx" in lower ||
                "comfort" in lower ||
                "electric" in lower ||
                "priority" in lower ||
                "exclusive" in lower ||
                "exclusivo" in lower ||
                "reserve" in lower ||
                "corresponder" in lower -> SourceApp.UBER
            else -> SourceApp.UNKNOWN
        }
    }

    private fun normalizeRawText(rawText: String): String =
        rawText
            .replace('\u00A0', ' ')
            .replace("\u00C2", "")
            .replace("â‚¬", "\u20AC")
            .replace("â†’", "\u2192")
            .replace("â€¢", "\u2022")
            .replace('\r', '\n')
            .trim()

    private fun MatchResult.toDurationMinutes(hoursIndex: Int, minutesIndex: Int): Double {
        val hours = groupValues.getOrNull(hoursIndex)?.toDoubleOrNull() ?: 0.0
        val minutes = groupValues.getOrNull(minutesIndex)?.toDoubleOrNull() ?: 0.0
        return (hours * 60.0) + minutes
    }

    private fun String.normalizeDecimal(): String = replace(",", ".")

    private fun String?.extractPostalPrefix(): String? {
        if (this == null) return null
        return strictPostalPattern.find(this)?.groupValues?.getOrNull(1)
    }

    private fun String.containsPostalOrStreetShape(): Boolean {
        val lower = lowercase()
        return extractPostalPrefix() != null || streetHints.any { it in lower }
    }

    private fun String.cleanAddressLine(): String =
        replace("Portugal", "")
            .replace("  ", " ")
            .trim()
            .trim(',')

    data class ParseInspection(
        val offer: TripOffer?,
        val sourceApp: SourceApp,
        val notes: List<String>
    )

    private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)

    private fun Double.format2(): String = String.format(java.util.Locale.US, "%.2f", this)

    private data class DurationDistanceBlock(
        val durationMin: Double,
        val distanceKm: Double?
    )

    private val allOverlayStatusNamesLower = setOf(
        // REJECT
        "viagem de merda", "corrida do inferno", "nunca mais", "foge daqui", "péssima viagem",
        "rota do caos", "táxi do desastre", "corrida maldita", "viagem azeda", "destino incerto",
        "atraso garantido", "sem esperança", "mau caminho", "buraco negro", "falha total",
        "que chatice", "pior não dá", "vai a pé", "nem pensar", "fiasco total", "que raiva",
        "mais vale andar", "trânsito infernal", "caos sobre rodas", "zero estrelas",
        // REVIEW
        "viagem complicada", "corrida falhada", "destino incerto", "viagem sem graça",
        "nem sempre corre bem", "quase perfeita", "entre solavancos", "rota duvidosa",
        "caminho difícil", "corrida atribulada", "serviço mediano", "viagem morna",
        "nada de especial", "mais ou menos", "assim assim", "sem emoção", "nem boa nem má",
        "vale o que vale", "à rasca", "vai andando", "corrida cansativa", "trânsito sem fim",
        "tempo perdido", "paciência ao volante", "espera eterna", "percurso complicado",
        "nem sempre fácil", "um dia de cada vez", "a caminho",
        // ACCEPT
        "viagem excelente", "viagem perfeita", "viagem cinco estrelas", "viagem top",
        "viagem premium", "viagem confortável", "viagem tranquila", "viagem segura",
        "viagem rápida", "viagem suave", "viagem impecável", "viagem sem stress",
        "viagem espetacular", "viagem inesquecível", "viagem exemplar", "serviço excelente"
    )

    private fun cleanOverlayText(text: String): String {
        val lines = text.lines()
        val cleanedLines = lines.filter { line ->
            !isOverlayLine(line.trim())
        }
        return cleanedLines.joinToString("\n")
    }

    private fun isOverlayLine(line: String): Boolean {
        if (line.isEmpty()) return false
        if (line.equals("Recusar", ignoreCase = true) || line.equals("Aceitar", ignoreCase = true)) {
            return true
        }
        if (line.contains("EUR/h", ignoreCase = true) || line.contains("EUR/km", ignoreCase = true)) {
            return true
        }
        if (line.contains("tarifa total", ignoreCase = true) || line.contains("por hora", ignoreCase = true) || line.contains("por km", ignoreCase = true)) {
            return true
        }
        if (line.contains("pickup", ignoreCase = true) && line.contains("total", ignoreCase = true) && line.contains("|")) {
            return true
        }
        if ((line.startsWith("P ") || line.startsWith("D ")) && line.contains("min") && line.contains("km")) {
            return true
        }
        if (line.contains("cumpre filtros", ignoreCase = true) || 
            line.contains("abaixo do bom", ignoreCase = true) || 
            line.contains("abaixo do minimo", ignoreCase = true) || 
            line.contains("abaixo do mínimo", ignoreCase = true) || 
            line.contains("demasiado longe", ignoreCase = true) || 
            line.contains("demasiado alto", ignoreCase = true) || 
            line.contains("Falta tarifa", ignoreCase = true) || 
            line.contains("Falta distancia", ignoreCase = true) || 
            line.contains("Falta tempo", ignoreCase = true) ||
            line.contains("Rentabilidade baixa", ignoreCase = true) ||
            line.contains("Retorno horario baixo", ignoreCase = true)) {
            return true
        }
        
        val lowerLine = line.lowercase()
        if (allOverlayStatusNamesLower.contains(lowerLine)) {
            return true
        }
        
        return false
    }

    private fun isTripActiveOrNavigation(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("dirija-se") ||
               lower.contains("dirigir-se") ||
               lower.contains("navegar") ||
               lower.contains("navigate") ||
               lower.contains("iniciar viagem") ||
               lower.contains("iniciar a viagem") ||
               lower.contains("start trip") ||
               lower.contains("terminar viagem") ||
               lower.contains("end trip") ||
               lower.contains("recolher") ||
               lower.contains("a recolher") ||
               lower.contains("picking up") ||
               lower.contains("pickup") ||
               lower.contains("deixe o cliente") ||
               lower.contains("drop off") ||
               lower.contains("partilhar viagem") ||
               lower.contains("share trip") ||
               lower.contains("adicionar paragem") ||
               lower.contains("add stop") ||
               lower.contains("próxima paragem") ||
               lower.contains("next stop") ||
               lower.contains("viagem em curso") ||
               lower.contains("trip in progress") ||
               lower.contains("encontrar-se com") ||
               lower.contains("meet rider") ||
               lower.contains("meet client") ||
               lower.contains("deslize para") ||
               lower.contains("slide to")
    }
}
