package pt.tripguard.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import pt.tripguard.app.data.DiagnosticsStore
import pt.tripguard.app.rules.SourceApp
import pt.tripguard.app.data.TripHistoryStore
import pt.tripguard.app.rules.TripFilterEngine
import pt.tripguard.app.rules.TripHistoryEntry
import pt.tripguard.app.rules.TripOffer
import pt.tripguard.app.rules.TripOfferParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class TripAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var overlayOfferText: TextView? = null
    private var overlayDecisionText: TextView? = null
    private var overlayCloseButton: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private val pendingSnapshotRunnablesByPackage = mutableMapOf<String, MutableList<Runnable>>()
    private val snapshotGenerationByPackage = mutableMapOf<String, Int>()
    private var latestUberSourceText: String = ""
    private var latestUberEventText: String = ""
    private var latestUberPromisingSourceText: String = ""
    private var latestUberPromisingEventText: String = ""
    private var lastUberOcrAttemptMs: Long = 0L
    private var lastUberOcrSignature: String = ""
    private var uberOcrInFlight: Boolean = false
    private var lastUberEventAtMs: Long = 0L
    private var lastUberScheduleAtMs: Long = 0L
    private var lastUberScheduleScore: Int = 0
    private var lastOverlayOfferApp: SourceApp = SourceApp.UNKNOWN
    private var lastOverlayShownAtMs: Long = 0L
    private var lastOverlayCompleteness: Int = 0

    private data class UberSnapshotCandidate(
        val eventText: String,
        val sourceText: String,
        val sourceScore: Int,
        val eventScore: Int,
        val combinedScore: Int
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val packageName = event?.packageName?.toString().orEmpty()
            if (!shouldHandlePackage(packageName)) return

            val eventType = event?.eventType ?: -1
            val eventText = event?.text
                ?.mapNotNull { it?.toString()?.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.joinToString("\n")
                .orEmpty()
            val eventSourceText = safeNodeDump(event?.source)
            val eventSourcePackage = runCatching {
                event?.source?.packageName?.toString().orEmpty()
            }.getOrDefault("").ifBlank { "-" }
            val diagnostics = DiagnosticsStore(this)
            diagnostics.append(
                "EVENT",
                "raw pkg=$packageName type=$eventType class=${event?.className ?: "-"} textCount=${event?.text?.size ?: 0} sourcePkg=$eventSourcePackage sourceChars=${eventSourceText.length}"
            )
            Log.d(
                TAG,
                "raw event pkg=$packageName type=$eventType class=${event?.className ?: "-"} sourcePkg=$eventSourcePackage sourceChars=${eventSourceText.length}"
            )

            if (sourceAppFromPackage(packageName) == SourceApp.UBER) {
                lastUberEventAtMs = System.currentTimeMillis()
                if (
                    eventSourcePackage.startsWith("com.ubercab") &&
                    scoreUberOfferCandidate(eventSourceText) >= 4
                ) {
                    latestUberSourceText = eventSourceText
                    if (looksPromisingUberSnapshot(eventSourceText)) {
                        latestUberPromisingSourceText = eventSourceText
                    }
                }
                if (eventText.isNotBlank()) {
                    latestUberEventText = eventText
                    if (looksPromisingUberSnapshot(eventText)) {
                        latestUberPromisingEventText = eventText
                    }
                }

                val candidate = buildBestUberSnapshotCandidate(
                    eventText = eventText,
                    eventSourceText = eventSourceText
                )

                val directUberCandidate =
                    eventSourcePackage.startsWith("com.ubercab") &&
                        candidate.combinedScore >= 10 &&
                        (
                            candidate.sourceText.length >= 40 ||
                                candidate.eventText.length >= 24 ||
                                looksPromisingUberSnapshot(candidate.sourceText)
                        )
                if (directUberCandidate) {
                    processPackageSnapshot(
                        packageName = packageName,
                        eventType = eventType,
                        eventText = candidate.eventText,
                        eventSourceText = candidate.sourceText,
                        forceSourceOnly = candidate.sourceScore >= candidate.eventScore
                    )
                    return
                }

                scheduleUberSnapshotSequence(
                    packageName = packageName,
                    eventType = eventType,
                    eventText = candidate.eventText,
                    eventSourceText = candidate.sourceText
                )
                return
            }

            processPackageSnapshot(packageName, eventType, eventText, eventSourceText)
        } catch (error: Throwable) {
            recordServiceError("EVENT", error)
        }
    }

    private fun processPackageSnapshot(
        packageName: String,
        eventType: Int,
        eventText: String,
        eventSourceText: String,
        forceSourceOnly: Boolean = false
    ) {
        try {
            if (!shouldHandlePackage(packageName)) return

            val diagnostics = DiagnosticsStore(this)
            val rawText = collectRawText(packageName, eventText, eventSourceText, forceSourceOnly)
            val windowPackages = windows.joinToString(",") { window ->
                window.root?.packageName?.toString().orEmpty().ifBlank { "unknown" }
            }
            if (rawText.isBlank()) {
                getSharedPreferences("tripguard", MODE_PRIVATE)
                    .edit()
                    .putString("last_seen_package", packageName)
                    .putString("last_raw_text_sample", "")
                    .putString(
                        "last_event_debug",
                        "pkg=$packageName | type=$eventType | windows=${windows.size} | pkgs=$windowPackages | eventChars=${eventText.length} | sourceChars=${eventSourceText.length} | sem texto util"
                    )
                    .putString("last_parse_debug", "Sem texto util em $packageName")
                    .apply()
                diagnostics.append(
                    "EVENT",
                    "pkg=$packageName type=$eventType pkgs=$windowPackages eventChars=${eventText.length} sourceChars=${eventSourceText.length} sem texto util"
                )
                return
            }

            getSharedPreferences("tripguard", MODE_PRIVATE)
                .edit()
                .putString("last_seen_package", packageName)
                .putString("last_raw_text_sample", rawText.take(4000))
                .putString(
                    "last_event_debug",
                    "pkg=$packageName | type=$eventType | windows=${windows.size} | pkgs=$windowPackages | eventChars=${eventText.length} | sourceChars=${eventSourceText.length} | text=${rawText.take(180)}"
                )
                .apply()
            diagnostics.append("EVENT", "pkg=$packageName type=$eventType windows=${windows.size} pkgs=$windowPackages chars=${rawText.length}")

            val offer = TripOfferParser.parse(rawText, sourceAppFromPackage(packageName))
            if (offer == null) {
                val rawPreview = rawText
                    .replace("\n", " | ")
                    .take(280)
                val launchedOcr =
                    sourceAppFromPackage(packageName) == SourceApp.UBER &&
                        maybeAttemptUberScreenshotOcr(packageName, rawText)
                getSharedPreferences("tripguard", MODE_PRIVATE)
                    .edit()
                    .putString(
                        "last_parse_debug",
                        if (launchedOcr) {
                            "Sem cartao valido em $packageName | OCR fallback lancado | chars=${rawText.length} | amostra=$rawPreview"
                        } else {
                            "Sem cartao valido em $packageName | chars=${rawText.length} | amostra=$rawPreview"
                        }
                    )
                    .apply()
                diagnostics.append(
                    "PARSE",
                    if (launchedOcr) {
                        "Sem cartao valido em $packageName | OCR fallback lancado | chars=${rawText.length} | amostra=$rawPreview"
                    } else {
                        "Sem cartao valido em $packageName | chars=${rawText.length} | amostra=$rawPreview"
                    }
                )
                return
            }

            clearPendingSnapshots(packageName)
            val result = TripFilterEngine.evaluate(offer)
            TripHistoryStore(this).save(
                TripHistoryEntry(
                    capturedAtMs = System.currentTimeMillis(),
                    offer = offer,
                    result = result
                )
            )

            getSharedPreferences("tripguard", MODE_PRIVATE)
                .edit()
                .putString(
                    "last_offer_summary",
                    buildOfferSummary(offer)
                )
                .putString(
                    "last_decision_summary",
                    "${result.decision}: ${result.reasons.joinToString(" | ")}"
                )
                .putString("last_parse_debug", "Oferta valida ${offer.sourceApp.name} tarifa=${offer.fareEur ?: "?"}")
                .apply()
            diagnostics.append("PARSE", "Oferta valida ${offer.sourceApp.name} tarifa=${offer.fareEur ?: "?"}")

            showOrUpdateOverlay(offer, result.reasons.joinToString(" | "))

            Log.d(TAG, "Parsed offer ${offer.rawText}")
            Log.d(TAG, "Decision ${result.decision} with reasons ${result.reasons}")
        } catch (error: Throwable) {
            recordServiceError("PROCESS", error)
        }
    }

    private fun buildOfferSummary(offer: TripOffer): String {
        fun Double?.fmt(decimals: Int = 1): String =
            this?.let { "%.${decimals}f".format(it).replace(",", ".") } ?: "?"

        fun Double?.money(): String =
            this?.let { "%.2f".format(it).replace(",", ".") } ?: "?"

        return buildString {
            append("App=${offer.sourceApp.name}")
            append(" | Tarifa=${offer.fareEur.money()} EUR")
            append(" | Recolha=${offer.pickupDistanceKm.fmt()} km / ${offer.pickupDurationMin.fmt(0)} min")
            append(" | Viagem=${offer.tripDistanceKm.fmt()} km / ${offer.tripDurationMin.fmt(0)} min")
            append(" | Total=${offer.totalDistanceKm().fmt()} km / ${offer.totalDurationMin().fmt(0)} min")
            append(" | ${offer.eurPerKm()?.let { "%.2f".format(it).replace(",", ".") } ?: "?"} EUR/km")
            append(" | ${offer.eurPerHour()?.let { "%.2f".format(it).replace(",", ".") } ?: "?"} EUR/h")
            append(" | PickupZone=${offer.pickupPostalCode ?: "?"}")
            append(" | DestZone=${offer.destinationPostalCode ?: "?"}")
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onDestroy() {
        clearAllPendingSnapshots()
        hideOverlay()
        super.onDestroy()
    }

    private fun clearPendingSnapshots(packageName: String) {
        val normalizedPackage = packageName.substringBefore("/")
        pendingSnapshotRunnablesByPackage.remove(normalizedPackage)
            ?.forEach(handler::removeCallbacks)
    }

    private fun clearAllPendingSnapshots() {
        pendingSnapshotRunnablesByPackage.values
            .flatten()
            .forEach(handler::removeCallbacks)
        pendingSnapshotRunnablesByPackage.clear()
        snapshotGenerationByPackage.clear()
    }

    private fun buildBestUberSnapshotCandidate(
        eventText: String,
        eventSourceText: String
    ): UberSnapshotCandidate {
        val bestSource = listOf(
            eventSourceText.takeIf { looksPromisingUberSnapshot(it) },
            latestUberPromisingSourceText,
            eventSourceText.takeIf { it.isNotBlank() },
            latestUberSourceText.takeIf { it.isNotBlank() }
        )
            .filterNotNull()
            .maxByOrNull { scoreUberOfferCandidate(it) * 10 + it.length.coerceAtMost(999) }
            .orEmpty()

        val bestEvent = listOf(
            eventText.takeIf { looksPromisingUberSnapshot(it) },
            latestUberPromisingEventText,
            eventText.takeIf { it.isNotBlank() },
            latestUberEventText.takeIf { it.isNotBlank() }
        )
            .filterNotNull()
            .maxByOrNull { scoreUberOfferCandidate(it) * 10 + it.length.coerceAtMost(999) }
            .orEmpty()

        val sourceScore = scoreUberOfferCandidate(bestSource)
        val eventScore = scoreUberOfferCandidate(bestEvent)
        val combinedScore = scoreUberOfferCandidate(
            listOf(bestSource, bestEvent)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        )

        return UberSnapshotCandidate(
            eventText = bestEvent,
            sourceText = bestSource,
            sourceScore = sourceScore,
            eventScore = eventScore,
            combinedScore = combinedScore
        )
    }

    private fun scheduleUberSnapshotSequence(
        packageName: String,
        eventType: Int,
        eventText: String,
        eventSourceText: String
    ) {
        val normalizedPackage = packageName.substringBefore("/")
        val currentScore = maxOf(
            scoreUberOfferCandidate(eventSourceText),
            scoreUberOfferCandidate(eventText)
        )
        val now = System.currentTimeMillis()
        val pendingForPackage = pendingSnapshotRunnablesByPackage[normalizedPackage]
        val shouldRefreshSequence =
            pendingForPackage.isNullOrEmpty() ||
                currentScore >= lastUberScheduleScore ||
                now - lastUberScheduleAtMs > 350L

        if (!shouldRefreshSequence) {
            return
        }

        clearPendingSnapshots(normalizedPackage)
        val nextGeneration = (snapshotGenerationByPackage[normalizedPackage] ?: 0) + 1
        snapshotGenerationByPackage[normalizedPackage] = nextGeneration
        lastUberScheduleAtMs = now
        lastUberScheduleScore = currentScore

        val runnables = mutableListOf<Runnable>()
        val delaysMs =
            if (
                looksPromisingUberSnapshot(eventSourceText) ||
                    looksPromisingUberSnapshot(eventText) ||
                    currentScore >= 10
            ) {
                listOf(35L, 100L, 220L, 420L, 700L, 1050L)
            } else {
                listOf(90L, 180L, 340L, 600L, 950L, 1350L)
            }

        delaysMs.forEach { delayMs ->
            val runnable = Runnable {
                if (snapshotGenerationByPackage[normalizedPackage] != nextGeneration) {
                    return@Runnable
                }
                val currentCandidate = buildBestUberSnapshotCandidate(
                    eventText = eventText,
                    eventSourceText = eventSourceText
                )
                processPackageSnapshot(
                    packageName = packageName,
                    eventType = eventType,
                    eventText = currentCandidate.eventText,
                    eventSourceText = currentCandidate.sourceText,
                    forceSourceOnly = currentCandidate.sourceScore >= currentCandidate.eventScore
                )
            }
            runnables += runnable
            handler.postDelayed(runnable, delayMs)
        }
        pendingSnapshotRunnablesByPackage[normalizedPackage] = runnables
    }

    private fun appendNodeText(node: AccessibilityNodeInfo, buffer: StringBuilder) {
        runCatching { node.text?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                buffer.appendLine(it)
            }

        runCatching { node.contentDescription?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                buffer.appendLine(it)
            }

        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            runCatching { appendNodeText(child, buffer) }
        }
    }

    private fun safeNodeDump(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        return runCatching {
            buildString { appendNodeText(node, this) }.trim()
        }.getOrDefault("")
    }

    private fun collectRawText(
        targetPackage: String,
        eventText: String,
        eventSourceText: String,
        forceSourceOnly: Boolean = false
    ): String {
        val blocks = mutableListOf<String>()
        val normalizedTargetPackage = targetPackage.substringBefore("/")
        val sourceApp = sourceAppFromPackage(targetPackage)

        if (forceSourceOnly && eventSourceText.isNotBlank()) {
            return "EVENT_SOURCE_DIRECT pkg=$normalizedTargetPackage\n$eventSourceText".trim()
        }

        if (sourceApp == SourceApp.UBER) {
            val focusedUberCard = collectFocusedUberCardText(
                targetPackage = normalizedTargetPackage,
                eventText = eventText,
                eventSourceText = eventSourceText
            )
            if (focusedUberCard.isNotBlank()) {
                return focusedUberCard
            }
        }

        rootInActiveWindow?.let { root ->
            val rootPackage = root.packageName?.toString().orEmpty()
            if (rootPackage.startsWith(normalizedTargetPackage)) {
                val activeText = buildString { appendNodeText(root, this) }.trim()
                if (activeText.isNotBlank()) {
                    blocks += "ACTIVE_ROOT pkg=$rootPackage\n$activeText"
                }
            }
        }

        windows.forEachIndexed { index, window ->
            val root = window.root ?: return@forEachIndexed
            val rootPackage = root.packageName?.toString().orEmpty()
            if (rootPackage == applicationContext.packageName) return@forEachIndexed
            if (!rootPackage.startsWith(normalizedTargetPackage)) return@forEachIndexed
            val windowText = buildString { appendNodeText(root, this) }.trim()
            if (windowText.isNotBlank()) {
                blocks += "WINDOW#$index pkg=${rootPackage.ifBlank { "unknown" }}\n$windowText"
            }
        }

        if (eventSourceText.isNotBlank()) {
            blocks += "EVENT_SOURCE pkg=$normalizedTargetPackage\n$eventSourceText"
        }

        if (eventText.isNotBlank()) {
            blocks += "EVENT_TEXT pkg=$normalizedTargetPackage\n$eventText"
        }

        if (sourceApp == SourceApp.UBER) {
            val activeRootPackage = rootInActiveWindow?.packageName?.toString().orEmpty().ifBlank { "unknown" }
            val visiblePackages = windows.joinToString(",") { window ->
                window.root?.packageName?.toString().orEmpty().ifBlank { "unknown" }
            }
            val hasUberWindow = windows.any { window ->
                window.root?.packageName?.toString().orEmpty().startsWith(normalizedTargetPackage)
            } || activeRootPackage.startsWith(normalizedTargetPackage)

            if (!hasUberWindow) {
                blocks += "UBER_NO_TARGET_WINDOW active=$activeRootPackage visible=$visiblePackages"
            }
        }

        return blocks
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")
            .trim()
    }

    private fun collectFocusedUberCardText(
        targetPackage: String,
        eventText: String,
        eventSourceText: String
    ): String {
        val candidates = mutableListOf<Pair<String, String>>()

        fun addCandidate(label: String, text: String) {
            val trimmed = text.trim()
            if (trimmed.isNotBlank()) {
                candidates += label to trimmed
            }
        }

        rootInActiveWindow?.let { root ->
            val rootPackage = root.packageName?.toString().orEmpty()
            val rootText = buildString { appendNodeText(root, this) }.trim()
            if (
                rootPackage.startsWith(targetPackage) &&
                (
                    looksPromisingUberSnapshot(rootText) ||
                        scoreUberOfferCandidate(rootText) >= 6
                )
            ) {
                addCandidate("ACTIVE_ROOT pkg=${rootPackage.ifBlank { targetPackage }}", rootText)
            }
        }

        windows.forEachIndexed { index, window ->
            val root = window.root ?: return@forEachIndexed
            val rootPackage = root.packageName?.toString().orEmpty().ifBlank { "unknown" }
            val rootText = buildString { appendNodeText(root, this) }.trim()
            if (
                rootPackage.startsWith(targetPackage) &&
                (
                    looksPromisingUberSnapshot(rootText) ||
                        scoreUberOfferCandidate(rootText) >= 6
                )
            ) {
                addCandidate("WINDOW#$index pkg=$rootPackage", rootText)
            }
        }

        addCandidate("EVENT_SOURCE pkg=$targetPackage", eventSourceText)
        addCandidate("EVENT_TEXT pkg=$targetPackage", eventText)

        val ranked = candidates
            .map { (label, text) -> Triple(label, text, scoreUberOfferCandidate(text)) }
            .sortedByDescending { it.third }

        DiagnosticsStore(this).append(
            "UBER_SCAN",
            ranked.take(4).joinToString(" || ") { (label, _, score) -> "$label score=$score" }
        )

        DiagnosticsStore(this).append(
            "UBER_TREE",
            collectUberStructuralHints(targetPackage).take(900)
        )

        val best = ranked.firstOrNull() ?: return ""
        if (best.third < 6) return ""

        val focusedText = extractFocusedUberOfferText(best.second)
        if (focusedText.isBlank()) return ""
        return "${best.first}\n$focusedText".trim()
    }

    private fun scoreUberOfferCandidate(text: String): Int {
        val normalized = text
            .lowercase()
            .replace('\n', ' ')
        var score = 0
        if (normalized.contains("apos deducao de taxa de servico")) score += 5
        if (normalized.contains("liquidos, incluindo impostos")) score += 5
        if (normalized.contains("viagem de")) score += 4
        if (normalized.contains("de distancia")) score += 4
        if (normalized.contains("oportunidade")) score += 4
        if (normalized.contains("aceitar")) score += 3
        if (normalized.contains("corresponder")) score += 3
        if (normalized.contains("exclusivo")) score += 2
        if (normalized.contains("mais de 30 min")) score += 2
        if (normalized.contains("destino extra")) score += 2
        if (Regex("""\d+[.,]\d{2}\s*(eur|€)""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) score += 3
        if (Regex("""\d+\s*min""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) score += 2
        if (Regex("""\d+[.,]?\d*\s*km""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) score += 2
        if (uberCategoryLabels().any { normalized.contains(it) }) score += 3
        if (normalized.contains("pagina inicial")) score -= 5
        if (normalized.contains("procurar locais")) score -= 4
        if (normalized.contains("uber pro blue")) score -= 4
        if (normalized.contains("ficar online novamente")) score -= 6
        if (normalized.contains("a procura e elevada")) score -= 6
        if (normalized.contains("ha bastante movimento")) score -= 6
        if (normalized.contains("tripguard")) score -= 8
        if (normalized.contains("historico recente capturado")) score -= 8
        if (normalized.contains("limpar diagnostico")) score -= 8
        if (normalized.contains("google")) score -= 3
        if (normalized.contains("maps")) score -= 3
        if (normalized.contains("galeria")) score -= 3
        if (normalized.contains("play store")) score -= 4
        if (normalized.contains("wa business")) score -= 4
        if (normalized.contains("o meu disco")) score -= 5
        if (normalized.contains("tripguard diagnostico")) score -= 8
        if (normalized.contains("rejeitar nao afeta a taxa de aceitacao")) score -= 6
        if (normalized.contains("fora do alcance")) score -= 6
        if (normalized.contains("portagem")) score -= 5
        if (normalized.contains("lucro")) score -= 5
        return score
    }

    private fun collectUberStructuralHints(targetPackage: String): String {
        val hints = mutableListOf<String>()

        rootInActiveWindow?.let { root ->
            val pkg = root.packageName?.toString().orEmpty().ifBlank { "unknown" }
            hints += "ACTIVE_ROOT pkg=$pkg :: ${describeNodeStructure(root, 0, 3, 28)}"
        }

        windows.forEachIndexed { index, window ->
            val root = window.root ?: return@forEachIndexed
            val pkg = root.packageName?.toString().orEmpty().ifBlank { "unknown" }
            if (!pkg.startsWith(targetPackage) && pkg == applicationContext.packageName) return@forEachIndexed
            hints += "WINDOW#$index pkg=$pkg :: ${describeNodeStructure(root, 0, 3, 28)}"
        }

        return hints.joinToString(" || ")
    }

    private fun uberVisibleWindowPackages(targetPackage: String): List<String> {
        val packages = windows.mapNotNull { window ->
            window.root?.packageName?.toString()
        }.map { it.ifBlank { "unknown" } }
        val active = rootInActiveWindow?.packageName?.toString()?.ifBlank { "unknown" }
        return buildList {
            addAll(packages)
            if (!active.isNullOrBlank()) add(active)
        }.distinct()
    }

    private fun isIgnoredForegroundPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        return packageName == applicationContext.packageName ||
            packageName.startsWith("com.miui.home") ||
            packageName.startsWith("com.android.systemui") ||
            packageName.startsWith("com.google.android.apps.nexuslauncher") ||
            packageName.startsWith("com.mi.android.globallauncher") ||
            packageName.startsWith("com.google.android.apps.photos") ||
            packageName.startsWith("com.google.android.apps.maps")
    }

    private fun hasVisibleUberWindow(targetPackage: String): Boolean =
        uberVisibleWindowPackages(targetPackage).any {
            it.startsWith(targetPackage) && !isIgnoredForegroundPackage(it)
        }

    private fun describeNodeStructure(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        remaining: Int
    ): String {
        if (remaining <= 0) return ""

        val label = buildString {
            append(node.className?.toString()?.substringAfterLast('.') ?: "Node")
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val payload = when {
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                else -> ""
            }.replace("\n", " ").take(32)
            if (payload.isNotBlank()) {
                append("[")
                append(payload)
                append("]")
            }
            if (node.isClickable) append("*")
            append("{")
            append(node.childCount)
            append("}")
        }

        if (depth >= maxDepth || remaining == 1 || node.childCount == 0) {
            return label
        }

        val childParts = mutableListOf<String>()
        val allowedChildren = minOf(node.childCount, remaining - 1, 4)
        for (index in 0 until allowedChildren) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            val childText = describeNodeStructure(
                node = child,
                depth = depth + 1,
                maxDepth = maxDepth,
                remaining = maxOf(1, (remaining - 1) / allowedChildren)
            )
            if (childText.isNotBlank()) {
                childParts += childText
            }
        }

        return if (childParts.isEmpty()) label else "$label -> (${childParts.joinToString(" | ")})"
    }

    private fun extractFocusedUberOfferText(text: String): String {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        val relevantIndexes = lines.mapIndexedNotNull { index, line ->
            val normalized = line.lowercase()
            val relevant =
                normalized.contains("apos deducao de taxa de servico") ||
                    normalized.contains("de distancia") ||
                    normalized.contains("viagem de") ||
                    normalized.contains("oportunidade") ||
                    normalized.contains("aceitar") ||
                    normalized.contains("corresponder") ||
                    normalized.contains("->") ||
                    normalized.contains("→") ||
                    normalized.contains("destino extra") ||
                    normalized.contains("mais de 30 min") ||
                    uberCategoryLabels().any { normalized.contains(it) } ||
                    Regex("""\d+[.,]\d{2}\s*(eur|€)""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
            if (relevant) index else null
        }
        if (relevantIndexes.isEmpty()) return ""

        val start = (relevantIndexes.min() - 2).coerceAtLeast(0)
        val end = (relevantIndexes.max() + 6).coerceAtMost(lines.lastIndex)
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private fun uberCategoryLabels(): List<String> = listOf(
        "uberx priority",
        "uberx and share",
        "uber intercity",
        "uberx",
        "comfort",
        "conforto",
        "electric",
        "electic"
    )

    private fun looksLikeUberOfferText(text: String): Boolean {
        val normalized = text
            .lowercase()
            .replace('\n', ' ')
        return (
            normalized.contains("apos deducao de taxa de servico") &&
                normalized.contains("viagem de") &&
                normalized.contains("de distancia")
            ) || normalized.contains("corresponder") ||
            normalized.contains("aceitar")
    }

    private fun looksPromisingUberSnapshot(text: String): Boolean {
        val normalized = text
            .lowercase()
            .replace('\n', ' ')
        val hasFare = Regex("""\d+[.,]\d{2}\s*(eur|€)""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
            Regex("""(eur|€)\s*\d+[.,]\d{2}""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val hasTripShape =
            normalized.contains("viagem de") ||
                normalized.contains("de distancia") ||
                normalized.contains("oportunidade") ||
                normalized.contains("corresponder") ||
                normalized.contains("aceitar")
        val hasCategory = uberCategoryLabels().any { normalized.contains(it) }
        val hasDistanceOrTime =
            Regex("""\d+\s*min""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) &&
                Regex("""\d+[.,]?\d*\s*km""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val hasArrowAddresses = normalized.contains("->") || normalized.contains("→")
        return hasFare && (hasTripShape || (hasCategory && hasDistanceOrTime) || (hasCategory && hasArrowAddresses))
    }

    private fun maybeAttemptUberScreenshotOcr(packageName: String, rawText: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (uberOcrInFlight && System.currentTimeMillis() - lastUberOcrAttemptMs > 2500L) {
            uberOcrInFlight = false
            DiagnosticsStore(this).append("OCR_RESET", "Uber OCR desbloqueado por timeout")
        }
        if (uberOcrInFlight) {
            DiagnosticsStore(this).append("OCR_SKIP", "Uber OCR ignorado: pedido anterior ainda em curso")
            return false
        }

        val normalizedTargetPackage = packageName.substringBefore("/")
        val visiblePackages = uberVisibleWindowPackages(normalizedTargetPackage)
        DiagnosticsStore(this).append(
            "OCR_CHECK",
            "Uber OCR check visible=${visiblePackages.joinToString(",")} rawChars=${rawText.length}"
        )

        val hasVisibleUberWindow = hasVisibleUberWindow(normalizedTargetPackage)
        val onlyIgnoredForegrounds =
            visiblePackages.isNotEmpty() &&
                visiblePackages.all { isIgnoredForegroundPackage(it) }
        val allowBlindAttempt = shouldAttemptBlindUberOcr(rawText)
        if (!hasVisibleUberWindow && onlyIgnoredForegrounds && !allowBlindAttempt) {
            DiagnosticsStore(this).append(
                "OCR_SKIP",
                "Uber OCR ignorado: foreground invalido (${visiblePackages.joinToString(",")})"
            )
            return false
        }
        if (!hasVisibleUberWindow && !allowBlindAttempt) {
            DiagnosticsStore(this).append(
                "OCR_SKIP",
                "Uber OCR ignorado: nenhuma janela Uber visivel; visible=${visiblePackages.joinToString(",")}"
            )
            return false
        }
        if (!hasVisibleUberWindow && allowBlindAttempt) {
            DiagnosticsStore(this).append(
                "OCR_BLIND",
                "Uber OCR forcado sem janela visivel; visible=${visiblePackages.joinToString(",")}"
            )
        }

        val now = System.currentTimeMillis()
        val signature = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(12)
            .joinToString("|")
            .take(240)
        if (signature.isNotBlank() && signature == lastUberOcrSignature && now - lastUberOcrAttemptMs < 3500L) {
            DiagnosticsStore(this).append("OCR_SKIP", "Uber OCR ignorado: snapshot repetido")
            return false
        }
        if (now - lastUberOcrAttemptMs < 1200L) {
            DiagnosticsStore(this).append("OCR_SKIP", "Uber OCR ignorado: throttled")
            return false
        }

        lastUberOcrAttemptMs = now
        lastUberOcrSignature = signature
        uberOcrInFlight = true
        DiagnosticsStore(this).append("OCR", "Uber OCR iniciado para $packageName")

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()

                    if (bitmap == null) {
                        uberOcrInFlight = false
                        DiagnosticsStore(this@TripAccessibilityService).append(
                            "OCR_ERROR",
                            "Falha a converter screenshot da Uber em bitmap"
                        )
                        return
                    }

                    runUberOcr(packageName, cropUberScreenshot(bitmap))
                }

                override fun onFailure(errorCode: Int) {
                    uberOcrInFlight = false
                    DiagnosticsStore(this@TripAccessibilityService).append(
                        "OCR_ERROR",
                        "takeScreenshot falhou para Uber com codigo=$errorCode"
                    )
                }
            }
        )
        return true
    }

    private fun runUberOcr(packageName: String, bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val ocrText = result.text
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .trim()

                DiagnosticsStore(this).append(
                    "OCR",
                    "Uber OCR concluido chars=${ocrText.length} amostra=${ocrText.replace("\n", " | ").take(260)}"
                )

                if (ocrText.isBlank()) {
                    getSharedPreferences("tripguard", MODE_PRIVATE)
                        .edit()
                        .putString("last_parse_debug", "Uber OCR sem texto util")
                        .apply()
                    return@addOnSuccessListener
                }

                val offer = TripOfferParser.parse(ocrText, SourceApp.UBER)
                if (offer == null) {
                    getSharedPreferences("tripguard", MODE_PRIVATE)
                        .edit()
                        .putString(
                            "last_parse_debug",
                            "Uber OCR sem cartao valido | chars=${ocrText.length} | amostra=${ocrText.replace("\n", " | ").take(280)}"
                        )
                        .putString("last_raw_text_sample", ocrText.take(4000))
                        .apply()
                    DiagnosticsStore(this).append(
                        "OCR_PARSE",
                        "Uber OCR sem cartao valido | chars=${ocrText.length}"
                    )
                    return@addOnSuccessListener
                }

                val resultDecision = TripFilterEngine.evaluate(offer)
                TripHistoryStore(this).save(
                    TripHistoryEntry(
                        capturedAtMs = System.currentTimeMillis(),
                        offer = offer.copy(rawText = "OCR_UBER\n${offer.rawText}"),
                        result = resultDecision
                    )
                )

                getSharedPreferences("tripguard", MODE_PRIVATE)
                    .edit()
                    .putString(
                        "last_offer_summary",
                        buildOfferSummary(offer)
                    )
                    .putString(
                        "last_decision_summary",
                        "${resultDecision.decision}: ${resultDecision.reasons.joinToString(" | ")}"
                    )
                    .putString("last_parse_debug", "Oferta valida UBER via OCR tarifa=${offer.fareEur ?: "?"}")
                    .putString("last_raw_text_sample", ocrText.take(4000))
                    .apply()
                DiagnosticsStore(this).append(
                    "OCR_PARSE",
                    "Oferta valida UBER via OCR tarifa=${offer.fareEur ?: "?"}"
                )

                showOrUpdateOverlay(offer, resultDecision.reasons.joinToString(" | "))
            }
            .addOnFailureListener { error ->
                DiagnosticsStore(this).append(
                    "OCR_ERROR",
                    "ML Kit OCR falhou na Uber: ${error.javaClass.simpleName}: ${error.message ?: "sem detalhe"}"
                )
            }
            .addOnCompleteListener {
                uberOcrInFlight = false
                recognizer.close()
                bitmap.recycle()
            }
    }

    private fun shouldAttemptBlindUberOcr(rawText: String): Boolean {
        val normalized = rawText.lowercase()
        val recentUberEvent = System.currentTimeMillis() - lastUberEventAtMs <= 5000L
        val hasFare =
            Regex("""\d+[.,]\d{2}\s*(eur|â‚¬)""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
                Regex("""(eur|â‚¬)\s*\d+[.,]\d{2}""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val hasStrongUberMarkers =
            normalized.contains("event_source pkg=com.ubercab.driver") ||
                normalized.contains("event_text pkg=com.ubercab.driver") ||
                normalized.contains("corresponder") ||
                normalized.contains("aceitar") ||
                normalized.contains("oportunidade") ||
                normalized.contains("apos deducao de taxa de servico") ||
                normalized.contains("exclusivo") ||
                normalized.contains("mais de 30 min") ||
                normalized.contains("destino extra") ||
                uberCategoryLabels().any { normalized.contains(it) }
        val hasTripShape =
            (
                Regex("""\d+\s*min""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) &&
                    Regex("""\d+[.,]?\d*\s*km""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
                ) ||
                (
                    uberCategoryLabels().any { normalized.contains(it) } &&
                        Regex("""\d+[.,]?\d*\s*km""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) &&
                        (normalized.contains("->") || normalized.contains("→") || normalized.contains("oportunidade"))
                    )
        val containsFalseForegroundMarkers =
            normalized.contains("google") ||
                normalized.contains("maps") ||
                normalized.contains("galeria") ||
                normalized.contains("play store") ||
                normalized.contains("tripguard") ||
                normalized.contains("o meu disco")
        return recentUberEvent &&
            hasFare &&
            hasStrongUberMarkers &&
            hasTripShape &&
            !containsFalseForegroundMarkers
    }

    private fun cropUberScreenshot(bitmap: Bitmap): Bitmap {
        if (bitmap.width < 40 || bitmap.height < 40) return bitmap
        val left = (bitmap.width * 0.04f).toInt().coerceAtLeast(0)
        val top = (bitmap.height * 0.22f).toInt().coerceAtLeast(0)
        val right = (bitmap.width * 0.96f).toInt().coerceAtMost(bitmap.width)
        val bottom = (bitmap.height * 0.96f).toInt().coerceAtMost(bitmap.height)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        if (width == bitmap.width && height == bitmap.height && left == 0 && top == 0) {
            return bitmap
        }
        val cropped = runCatching { Bitmap.createBitmap(bitmap, left, top, width, height) }.getOrNull()
        if (cropped == null) return bitmap
        if (cropped != bitmap) {
            bitmap.recycle()
        }
        return cropped
    }

    private fun showOrUpdateOverlay(offer: TripOffer, decisionSummary: String) {
        if (windowManager == null) return
        val now = System.currentTimeMillis()
        val newCompleteness = overlayCompletenessScore(offer)
        val overlayRecentlyShown = now - lastOverlayShownAtMs <= 1800L
        if (
            overlayRecentlyShown &&
                lastOverlayOfferApp != SourceApp.UNKNOWN &&
                lastOverlayOfferApp != offer.sourceApp &&
                newCompleteness + 1 < lastOverlayCompleteness
        ) {
            DiagnosticsStore(this).append(
                "OVERLAY",
                "Overlay ignorado para ${offer.sourceApp.name}: cartao concorrente menos completo (${newCompleteness} < $lastOverlayCompleteness)"
            )
            return
        }

        if (overlayView == null) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 26, 28, 26)
                isClickable = true
                isFocusable = false
            }

            overlayOfferText = TextView(this).apply {
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 18f
            }
            overlayDecisionText = TextView(this).apply {
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 13f
            }

            val closeButton = Button(this).apply {
                text = "Fechar"
                background = roundedDrawable("#1F2937", "#374151")
                setTextColor(Color.parseColor("#F8FAFC"))
                minHeight = 84
                minimumWidth = 180
                setPadding(22, 18, 22, 18)
                setOnClickListener { hideOverlay() }
                setOnTouchListener { _, _ ->
                    hideOverlay()
                    true
                }
            }
            overlayCloseButton = closeButton

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            headerRow.addView(
                overlayOfferText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            headerRow.addView(
                closeButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 16
                }
            )

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val acceptButton = Button(this).apply {
                text = "Aceitar"
                background = roundedDrawable("#2563EB", "#60A5FA")
                setTextColor(Color.parseColor("#FFFFFF"))
                setOnClickListener { performManualAction(true) }
            }

            val rejectButton = Button(this).apply {
                text = "Recusar"
                background = roundedDrawable("#111827", "#475569")
                setTextColor(Color.parseColor("#F8FAFC"))
                setOnClickListener { performManualAction(false) }
            }

            actionsRow.addView(
                acceptButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            actionsRow.addView(
                rejectButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 16
                }
            )

            container.addView(headerRow)
            container.addView(
                overlayDecisionText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 14
                    bottomMargin = 20
                }
            )
            container.addView(actionsRow)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                x = 24
                y = 64
                width = resources.displayMetrics.widthPixels - 48
            }

            windowManager?.addView(container, params)
            overlayView = container
        }

        handler.removeCallbacks(hideOverlayRunnable)
        val tone = overlayTone(offer, decisionSummary)
        val overlayDurationSeconds = loadOverlayDurationSeconds()
        overlayView?.background = GradientDrawable().apply {
            cornerRadius = 30f
            setColor(Color.parseColor("#0F172A"))
            setStroke(4, Color.parseColor(tone))
        }
        overlayCloseButton?.text = "Fechar (${overlayDurationSeconds}s)"
        overlayOfferText?.text = formatOverlayOffer(offer)
        overlayDecisionText?.text = "Fecha em ${overlayDurationSeconds}s | $decisionSummary"
        lastOverlayOfferApp = offer.sourceApp
        lastOverlayShownAtMs = now
        lastOverlayCompleteness = newCompleteness
        handler.postDelayed(hideOverlayRunnable, overlayDurationSeconds * 1000L)
    }

    private fun formatOverlayOffer(offer: TripOffer): String {
        val totalDistance = offer.totalDistanceKm()
        val totalDuration = offer.totalDurationMin()
        return buildString {
            append("${offer.sourceApp.name}  ${formatMoney(offer.fareEur)} EUR")
            append("  |  ${formatMoney(offer.eurPerKm())} EUR/km")
            append("  |  ${formatMoney(offer.eurPerHour())} EUR/h\n")
            append("Total: ${formatDistance(totalDistance)} / ${formatDuration(totalDuration)}\n")
            append("P: ${formatDuration(offer.pickupDurationMin)} - ${formatDistance(offer.pickupDistanceKm)}")
            offer.pickupPostalCode?.let { append(" ($it)") }
            append("\n")
            append("D: ${formatDuration(offer.tripDurationMin)} - ${formatDistance(offer.tripDistanceKm)}")
            offer.destinationPostalCode?.let { append(" ($it)") }
        }
    }

    private fun formatMoney(value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f", it).replace(".", ",") } ?: "?"

    private fun formatDistance(value: Double?): String =
        value?.let { "${String.format(Locale.US, "%.1f", it).replace(".", ",")} km" } ?: "? km"

    private fun formatDuration(value: Double?): String =
        value?.let { "${String.format(Locale.US, "%.0f", it)} min" } ?: "? min"

    private fun overlayCompletenessScore(offer: TripOffer): Int {
        var score = 0
        if (offer.fareEur != null) score += 3
        if (offer.pickupDurationMin != null) score += 2
        if (offer.pickupDistanceKm != null) score += 2
        if (offer.tripDurationMin != null) score += 2
        if (offer.tripDistanceKm != null) score += 2
        if (!offer.pickupAddress.isNullOrBlank()) score += 1
        if (!offer.destinationAddress.isNullOrBlank()) score += 1
        if (!offer.pickupPostalCode.isNullOrBlank()) score += 1
        if (!offer.destinationPostalCode.isNullOrBlank()) score += 1
        return score
    }

    private fun overlayTone(offer: TripOffer, decisionSummary: String): String {
        val normalized = decisionSummary.lowercase()
        return when {
            normalized.contains("blocked") ||
                normalized.contains("low ") ||
                normalized.contains("below") ||
                normalized.contains("too far") ||
                normalized.contains("reject") -> "#FB7185"
            offer.sourceApp == SourceApp.BOLT -> "#22C55E"
            offer.sourceApp == SourceApp.UBER -> "#60A5FA"
            else -> "#F59E0B"
        }
    }

    private fun roundedDrawable(fillColor: String, strokeColor: String): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 22f
            setColor(Color.parseColor(fillColor))
            setStroke(2, Color.parseColor(strokeColor))
        }

    private fun performManualAction(accept: Boolean) {
        val clicked = if (accept) {
            clickBestMatch(listOf("aceitar", "accept", "confirm"))
        } else {
            clickBestMatch(listOf("recusar", "reject", "decline", "ignore"))
        }

        getSharedPreferences("tripguard", MODE_PRIVATE)
            .edit()
            .putString(
                "last_manual_action_summary",
                if (clicked) {
                    if (accept) "Botao manual de aceitar executado" else "Botao manual de recusar executado"
                } else {
                    if (accept) "Carregaste em aceitar, mas nao encontrei botao correspondente no ecra"
                    else "Carregaste em recusar, mas nao encontrei botao correspondente no ecra"
                }
            )
            .apply()
    }

    private fun clickBestMatch(keywords: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        return findAndClick(root, keywords)
    }

    private fun shouldHandlePackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == applicationContext.packageName) return false
        return packageName.startsWith("com.ubercab.driver") ||
            packageName.startsWith("com.ubercab") ||
            packageName.startsWith("ee.mtakso.driver")
    }

    private fun sourceAppFromPackage(packageName: String): SourceApp =
        when {
            packageName.startsWith("ee.mtakso.driver") -> SourceApp.BOLT
            packageName.startsWith("com.ubercab.driver") || packageName.startsWith("com.ubercab") -> SourceApp.UBER
            else -> SourceApp.UNKNOWN
        }

    private fun hideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable)
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
                .onFailure { error -> Log.w(TAG, "Falha ao remover overlay", error) }
        }
        overlayView = null
        overlayOfferText = null
        overlayDecisionText = null
        overlayCloseButton = null
    }

    private fun loadOverlayDurationSeconds(): Int =
        getSharedPreferences("tripguard", MODE_PRIVATE)
            .getInt("overlay_duration_seconds", 4)
            .coerceIn(2, 15)

    private fun recordServiceError(stage: String, error: Throwable) {
        val packageHint = runCatching { rootInActiveWindow?.packageName?.toString().orEmpty() }
            .getOrDefault("")
        getSharedPreferences("tripguard", MODE_PRIVATE)
            .edit()
            .putString("last_seen_package", packageHint.ifBlank { "erro-interno" })
            .putString("last_parse_debug", "ERRO $stage: ${error.javaClass.simpleName}: ${error.message ?: "sem detalhe"}")
            .apply()
        DiagnosticsStore(this).append(
            "ERROR",
            "$stage ${error.javaClass.simpleName}: ${error.message ?: "sem detalhe"}"
        )
        Log.e(TAG, "Falha no servico em $stage", error)
        hideOverlay()
    }

    private fun findAndClick(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
            .lowercase()

        if (keywords.any { text.contains(it) }) {
            var clickableNode: AccessibilityNodeInfo? = node
            while (clickableNode != null) {
                if (clickableNode.isClickable && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                clickableNode = clickableNode.parent
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (findAndClick(child, keywords)) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "TripGuardService"
    }
}
