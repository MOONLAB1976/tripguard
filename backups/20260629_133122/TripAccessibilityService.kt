package pt.tripguard.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
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

class TripAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var overlayOfferText: TextView? = null
    private var overlayDecisionText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private var pendingUberReadRunnable: Runnable? = null
    private var pendingUberRetryRunnable: Runnable? = null

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
                val richUberSource =
                    eventSourcePackage.startsWith("com.ubercab") &&
                        eventSourceText.length >= 120 &&
                        looksLikeUberOfferText(eventSourceText)
                if (richUberSource) {
                    processPackageSnapshot(
                        packageName = packageName,
                        eventType = eventType,
                        eventText = eventText,
                        eventSourceText = eventSourceText,
                        forceSourceOnly = true
                    )
                    return
                }

                pendingUberReadRunnable?.let(handler::removeCallbacks)
                pendingUberRetryRunnable?.let(handler::removeCallbacks)
                pendingUberReadRunnable = Runnable {
                    processPackageSnapshot(packageName, eventType, eventText, eventSourceText)
                }
                pendingUberRetryRunnable = Runnable {
                    processPackageSnapshot(packageName, eventType, eventText, eventSourceText)
                }
                handler.postDelayed(pendingUberReadRunnable!!, 700L)
                handler.postDelayed(pendingUberRetryRunnable!!, 1200L)
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
                hideOverlay()
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
                getSharedPreferences("tripguard", MODE_PRIVATE)
                    .edit()
                    .putString(
                        "last_parse_debug",
                        "Sem cartao valido em $packageName | chars=${rawText.length} | amostra=$rawPreview"
                    )
                    .apply()
                diagnostics.append(
                    "PARSE",
                    "Sem cartao valido em $packageName | chars=${rawText.length} | amostra=$rawPreview"
                )
                hideOverlay()
                return
            }

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
                    "App=${offer.sourceApp.name} | Tarifa=${offer.fareEur ?: "?"} EUR | Recolha=${offer.pickupDistanceKm ?: "?"} km | Viagem=${offer.tripDistanceKm ?: "?"} km | ${offer.eurPerKm()?.let { "%.2f".format(it) } ?: "?"} EUR/km | ${offer.eurPerHour()?.let { "%.2f".format(it) } ?: "?"} EUR/h | PickupZone=${offer.pickupPostalCode ?: "?"} | DestZone=${offer.destinationPostalCode ?: "?"}"
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

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onDestroy() {
        pendingUberReadRunnable?.let(handler::removeCallbacks)
        pendingUberRetryRunnable?.let(handler::removeCallbacks)
        hideOverlay()
        super.onDestroy()
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

        if (forceSourceOnly && eventSourceText.isNotBlank()) {
            return "EVENT_SOURCE_DIRECT pkg=$normalizedTargetPackage\n$eventSourceText".trim()
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

        if (sourceAppFromPackage(targetPackage) == SourceApp.UBER) {
            windows.forEachIndexed { index, window ->
                val root = window.root ?: return@forEachIndexed
                val rootPackage = root.packageName?.toString().orEmpty()
                if (rootPackage == applicationContext.packageName) return@forEachIndexed
                if (rootPackage.startsWith(normalizedTargetPackage)) return@forEachIndexed
                val windowText = buildString { appendNodeText(root, this) }.trim()
                if (windowText.isNotBlank()) {
                    blocks += "UBER_FALLBACK_WINDOW#$index pkg=${rootPackage.ifBlank { "unknown" }}\n$windowText"
                }
            }
        }

        return blocks
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")
            .trim()
    }

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

    private fun showOrUpdateOverlay(offer: TripOffer, decisionSummary: String) {
        if (windowManager == null) return
        if (overlayView == null) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                background = GradientDrawable().apply {
                    cornerRadius = 28f
                    setColor(Color.parseColor("#F7F9FC"))
                    setStroke(2, Color.parseColor("#D0D5DD"))
                }
                setOnClickListener { hideOverlay() }
            }

            overlayOfferText = TextView(this).apply {
                setTextColor(Color.parseColor("#101820"))
                textSize = 15f
            }
            overlayDecisionText = TextView(this).apply {
                setTextColor(Color.parseColor("#475467"))
                textSize = 12f
            }

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val acceptButton = Button(this).apply {
                text = "Aceitar"
                setOnClickListener { performManualAction(true) }
            }

            val rejectButton = Button(this).apply {
                text = "Recusar"
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

            container.addView(overlayOfferText)
            container.addView(
                overlayDecisionText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12
                    bottomMargin = 18
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
        overlayOfferText?.text =
            "${offer.sourceApp.name}  ${offer.fareEur ?: "?"} EUR  ${offer.eurPerKm()?.let { "%.2f".format(it) } ?: "?"} EUR/km  ${offer.eurPerHour()?.let { "%.2f".format(it) } ?: "?"} EUR/h"
        overlayDecisionText?.text =
            "Recolha ${offer.pickupPostalCode ?: "----"}  Destino ${offer.destinationPostalCode ?: "----"}  |  $decisionSummary"
        handler.postDelayed(hideOverlayRunnable, 4000L)
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
    }

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
