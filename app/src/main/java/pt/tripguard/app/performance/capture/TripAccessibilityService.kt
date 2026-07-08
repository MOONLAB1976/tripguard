package pt.tripguard.app.performance.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import pt.tripguard.app.core.domain.EvaluationResult
import pt.tripguard.app.core.domain.OfferDecision
import pt.tripguard.app.core.domain.SourceApp
import pt.tripguard.app.core.domain.TripFilterEngine
import pt.tripguard.app.core.domain.TripHistoryEntry
import pt.tripguard.app.core.domain.TripOffer
import pt.tripguard.app.core.storage.CardTheme
import pt.tripguard.app.core.storage.CaptureDebugEntry
import pt.tripguard.app.core.storage.CaptureDebugStore
import pt.tripguard.app.core.storage.HorizontalPosition
import pt.tripguard.app.core.storage.TripGuardSettingsStore
import pt.tripguard.app.core.storage.TripGuardSettings
import pt.tripguard.app.core.storage.VerticalPosition
import pt.tripguard.app.core.storage.TripHistoryStore
import android.graphics.Bitmap
import android.view.Display
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.io.File
import kotlin.math.max

class TripAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var overlaySourceBadgeText: TextView? = null
    private var overlaySourceNameText: TextView? = null
    private var overlayStatusText: TextView? = null
    private var overlayFareValueText: TextView? = null
    private var overlayFareLabelText: TextView? = null
    private var overlayHourValueText: TextView? = null
    private var overlayHourLabelText: TextView? = null
    private var overlayKmValueText: TextView? = null
    private var overlayKmLabelText: TextView? = null
    private var overlayCompactSummaryText: TextView? = null
    private var overlayPickupLegText: TextView? = null
    private var overlayPickupAddressText: TextView? = null
    private var overlayDropLegText: TextView? = null
    private var overlayDropAddressText: TextView? = null
    private var overlayDecisionText: TextView? = null
    private var overlayAcceptButton: Button? = null
    private var overlayRejectButton: Button? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var currentOverlayFingerprint: String? = null
    private var dismissedOverlayFingerprint: String? = null
    private var lastProcessedAtMs: Long = 0L
    private var captureScheduled = false
    private var pendingEventText: String = ""
    private var pendingEventPackage: String? = null
    private var uberOcrInFlight = false
    private var lastUberOcrAttemptMs = 0L
    private var lastUberOcrSignature = ""
    private var lastOverlayShownAtMs = 0L
    private var dismissedOcrSignature = ""      // assinatura do ecra OCR ja dispensado
    private var currentTripStatusName: String? = null   // nome atual da viagem (muda so quando a viagem muda)
    private var lastStatusNameFingerprint: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val historyStore by lazy { TripHistoryStore(this) }
    private val captureDebugStore by lazy { CaptureDebugStore(this) }
    private val settingsStore by lazy { TripGuardSettingsStore(this) }
    private val recentOfferFingerprints = LinkedHashMap<String, Long>()

    private val captureRunnable = Runnable {
        captureScheduled = false
        processCurrentScreen()
    }
    private val autoHideRunnable = Runnable {
        dismissedOverlayFingerprint = currentOverlayFingerprint
        dismissedOcrSignature = lastUberOcrSignature  // nao relançar OCR para o mesmo ecra
        hideOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRelevantEvent(event)) return
        if (!settingsStore.read().tripGuardEnabled) {
            hideOverlay()
            return
        }

        pendingEventText = event?.text?.joinToString(" ").orEmpty()
        pendingEventPackage = event?.packageName?.toString()

        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastProcessedAtMs
        if (elapsed >= MIN_PROCESS_INTERVAL_MS) {
            mainHandler.removeCallbacks(captureRunnable)
            captureScheduled = false
            processCurrentScreen()
        } else if (!captureScheduled) {
            captureScheduled = true
            mainHandler.postDelayed(captureRunnable, MIN_PROCESS_INTERVAL_MS - elapsed)
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.removeCallbacks(autoHideRunnable)
        hideOverlay()
        super.onDestroy()
    }

    private fun isRelevantEvent(event: AccessibilityEvent?): Boolean {
        val packageName = event?.packageName?.toString()
        val eventText = event.textSummary()
        if (packageName == this.packageName) return false

        return packageName == null ||
            packageName.isKnownDriverPackage() ||
            eventText.hasOfferSignal()
    }

    private fun processCurrentScreen() {
        lastProcessedAtMs = SystemClock.uptimeMillis()
        val settings = settingsStore.read()

        val captures = collectWindowText()
        if (captures.isEmpty()) {
            appendCaptureDebug(
                packageName = pendingEventPackage,
                sourceHint = pendingEventPackage.toSourceApp(),
                stage = "capture-empty",
                detail = "No accessibility window text captured",
                excerpt = pendingEventText
            )
            return
        }

        val activePackage = rootInActiveWindow?.packageName?.toString() ?: pendingEventPackage
        val activeApp = activePackage?.toSourceApp() ?: SourceApp.UNKNOWN

        val parsedOffers = captures
            .asSequence()
            .filter { it.text.hasOfferSignal() || it.sourceHint != SourceApp.UNKNOWN }
            .mapNotNull { capture ->
                val inspection = TripOfferParser.inspect(capture.text, capture.sourceHint)
                val detail = inspection.notes.joinToString(" | ")
                val excerpt = capture.text.toDebugExcerpt()
                val parsedOffer = inspection.offer
                if (parsedOffer == null) {
                    appendCaptureDebug(
                        packageName = capture.packageName,
                        sourceHint = inspection.sourceApp,
                        stage = "parse-miss",
                        detail = detail,
                        excerpt = excerpt
                    )
                    null
                } else {
                    val confidence = capture.confidenceFor(parsedOffer)
                    appendCaptureDebug(
                        packageName = capture.packageName,
                        sourceHint = parsedOffer.sourceApp,
                        stage = "parse-hit",
                        detail = "$detail | confidence=$confidence",
                        excerpt = excerpt
                    )
                    CapturedOffer(parsedOffer, confidence, parsedOffer.fingerprint())
                }
            }
            .filter {
                val enabled = settings.isPlatformEnabled(it.offer.sourceApp)
                if (!enabled) {
                    appendCaptureDebug(
                        packageName = null,
                        sourceHint = it.offer.sourceApp,
                        stage = "filtered-platform",
                        detail = "Platform disabled for ${it.offer.sourceApp}",
                        excerpt = it.offer.rawText.toDebugExcerpt()
                    )
                }
                enabled
            }
            .filter {
                val accepted = it.confidence >= MIN_CAPTURE_CONFIDENCE
                if (!accepted) {
                    appendCaptureDebug(
                        packageName = null,
                        sourceHint = it.offer.sourceApp,
                        stage = "filtered-confidence",
                        detail = "confidence=${it.confidence} threshold=$MIN_CAPTURE_CONFIDENCE",
                        excerpt = it.offer.rawText.toDebugExcerpt()
                    )
                }
                accepted
            }
            .distinctBy { it.fingerprint }
            .sortedWith(
                compareByDescending<CapturedOffer> { it.offer.sourceApp == activeApp && activeApp != SourceApp.UNKNOWN }
                    .thenByDescending { it.confidence }
            )
            .toList()

        if (parsedOffers.isEmpty()) {
            val launchedOcr = (pendingEventPackage?.toSourceApp() == SourceApp.UBER) &&
                maybeAttemptUberScreenshotOcr(pendingEventPackage ?: "com.ubercab.driver", pendingEventText)

            appendCaptureDebug(
                packageName = pendingEventPackage,
                sourceHint = pendingEventPackage.toSourceApp(),
                stage = if (launchedOcr) "ocr-fallback-launch" else "no-candidate",
                detail = if (launchedOcr) "Launched ML Kit OCR fallback" else "No parsed offer survived filters",
                excerpt = pendingEventText
            )
            if (!launchedOcr) {
                val settings = settingsStore.read()
                val currentPackage = pendingEventPackage
                val inDriverApp = currentPackage == "com.ubercab.driver" || currentPackage == "com.bolt.driver" || currentPackage == "pt.tripguard.app"
                
                val durationMs = settings.durationSeconds * 1000L
                val inGrace = System.currentTimeMillis() - lastOverlayShownAtMs < durationMs
                
                if (!inDriverApp || !inGrace) {
                    hideOverlay()
                    currentOverlayFingerprint = null
                }
            }
            return
        }

        var overlayCandidate: Pair<TripOffer, EvaluationResult>? = null
        for (captured in parsedOffers) {
            val result = TripFilterEngine.evaluate(captured.offer, settings.toFilterConfig())
            if (!rememberIfFresh(captured.fingerprint)) {
                appendCaptureDebug(
                    packageName = null,
                    sourceHint = captured.offer.sourceApp,
                    stage = "dedupe-skip",
                    detail = "fingerprint=${captured.fingerprint}",
                    excerpt = captured.offer.rawText.toDebugExcerpt()
                )
                if (overlayCandidate == null && dismissedOverlayFingerprint != captured.fingerprint) {
                    overlayCandidate = captured.offer to result
                }
                continue
            }

            historyStore.save(
                TripHistoryEntry(
                    capturedAtMs = System.currentTimeMillis(),
                    offer = captured.offer,
                    result = result
                )
            )
            writeLastSummaries(captured.offer, result)
            appendCaptureDebug(
                packageName = null,
                sourceHint = captured.offer.sourceApp,
                stage = "saved-offer",
                detail = "decision=${result.decision} reasons=${result.reasons.joinToString(" | ")}",
                excerpt = captured.offer.rawText.toDebugExcerpt()
            )

            if (overlayCandidate == null && dismissedOverlayFingerprint != captured.fingerprint) {
                overlayCandidate = captured.offer to result
            }

            Log.d(TAG, "Parsed ${captured.offer.sourceApp} offer confidence=${captured.confidence} ${captured.offer.rawText}")
            Log.d(TAG, "Decision ${result.decision} with reasons ${result.reasons}")
        }

        overlayCandidate?.let { (offer, result) ->
            currentOverlayFingerprint = offer.fingerprint()
            appendCaptureDebug(
                packageName = null,
                sourceHint = offer.sourceApp,
                stage = "overlay-show",
                detail = "fingerprint=${offer.fingerprint()} decision=${result.decision}",
                excerpt = offer.rawText.toDebugExcerpt()
            )
            showOrUpdateOverlay(offer, result, settings)
        }
    }

    private fun collectWindowText(): List<WindowCapture> {
        val captures = mutableListOf<WindowCapture>()
        val eventPackage = pendingEventPackage
        val eventText = pendingEventText

        windows
            ?.asSequence()
            ?.mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                val packageName = root.packageName?.toString() ?: eventPackage
                if (packageName == this.packageName) return@mapNotNull null

                val sourceHint = packageName.toSourceApp()
                val text = buildNodeText(root)
                if (text.isBlank()) {
                    null
                } else {
                    WindowCapture(packageName, sourceHint, listOf(eventText, text).compactText())
                }
            }
            ?.toCollection(captures)

        if (captures.isEmpty()) {
            val root = rootInActiveWindow ?: return emptyList()
            val packageName = root.packageName?.toString() ?: eventPackage
            captures += WindowCapture(
                packageName = packageName,
                sourceHint = packageName.toSourceApp(),
                text = listOf(eventText, buildNodeText(root)).compactText()
            )
        }

        return captures
            .filter { it.text.isNotBlank() }
            .distinctBy { "${it.packageName}:${it.text.fastTextFingerprint()}" }
    }

    private fun buildNodeText(root: AccessibilityNodeInfo): String {
        val buffer = StringBuilder(MAX_TEXT_CHARS.coerceAtMost(4096))
        val limits = TraversalLimits()
        appendNodeText(root, buffer, limits)
        return buffer.toString().trim()
    }

    private fun appendNodeText(
        node: AccessibilityNodeInfo,
        buffer: StringBuilder,
        limits: TraversalLimits
    ) {
        if (limits.nodesVisited >= MAX_NODE_COUNT || buffer.length >= MAX_TEXT_CHARS) return
        limits.nodesVisited++

        appendNodeField(node.text, buffer)
        appendNodeField(node.contentDescription, buffer)

        for (index in 0 until node.childCount) {
            if (limits.nodesVisited >= MAX_NODE_COUNT || buffer.length >= MAX_TEXT_CHARS) break
            val child = node.getChild(index) ?: continue
            try {
                appendNodeText(child, buffer, limits)
            } finally {
                child.recycle()
            }
        }
    }

    private fun appendNodeField(value: CharSequence?, buffer: StringBuilder) {
        if (value == null || buffer.length >= MAX_TEXT_CHARS) return
        val text = value.toString().trim()
        if (text.isBlank()) return

        val available = MAX_TEXT_CHARS - buffer.length
        if (available <= 0) return
        buffer.append(text.take(available))
        buffer.append('\n')
    }

    private fun rememberIfFresh(fingerprint: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val lastSeen = recentOfferFingerprints[fingerprint]
        recentOfferFingerprints[fingerprint] = now

        val iterator = recentOfferFingerprints.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > OFFER_DEDUPE_WINDOW_MS) {
                iterator.remove()
            }
        }

        return lastSeen == null || now - lastSeen > OFFER_DEDUPE_WINDOW_MS
    }

    private fun writeLastSummaries(offer: TripOffer, result: EvaluationResult) {
        val pickupKm = offer.pickupDistanceKm?.km() ?: "?"
        val tripKm = offer.tripDistanceKm?.km() ?: "?"
        val totalKm = offer.totalDistanceKm()?.km() ?: "?"
        val pickupMin = offer.pickupDurationMin?.minutes() ?: "?"
        val tripMin = offer.tripDurationMin?.minutes() ?: "?"
        val totalMin = offer.totalDurationMin()?.minutes() ?: "?"
        getSharedPreferences("tripguard", MODE_PRIVATE)
            .edit()
            .putString(
                "last_offer_summary",
                "App=${offer.sourceApp.name} | Tarifa=${offer.fareEur?.money() ?: "?"} EUR | Recolha=$pickupKm km | Viagem=$tripKm km | Total=$totalKm km | Tempo recolha=$pickupMin min | Tempo viagem=$tripMin min | Tempo total=$totalMin min | ${offer.eurPerKm()?.let { "%.2f".format(Locale.US, it) } ?: "?"} EUR/km | ${offer.eurPerHour()?.let { "%.2f".format(Locale.US, it) } ?: "?"} EUR/h | PickupZone=${offer.pickupPostalCode ?: "?"} | DestZone=${offer.destinationPostalCode ?: "?"}"
            )
            .putString(
                "last_decision_summary",
                "${result.decision}: ${result.reasons.joinToString(" | ")}"
            )
            .apply()
    }

    private fun showOrUpdateOverlay(offer: TripOffer, result: EvaluationResult, settings: TripGuardSettings) {
        lastOverlayShownAtMs = System.currentTimeMillis()
        if (windowManager == null) return
        val palette = result.decision.toOverlayPalette(settings.cardTheme)
        if (overlayView == null) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 24, 28, 24)
                background = roundedBackground(palette.surfaceColor, palette.strokeColor, 34f, 4)
            }

            val headerRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }

            overlaySourceBadgeText = TextView(this).apply {
                minWidth = 110
                gravity = Gravity.CENTER
                setPadding(18, 12, 18, 12)
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                background = roundedBackground(Color.parseColor("#050505"), Color.parseColor("#050505"), 16f, 0)
            }

            overlaySourceNameText = TextView(this).apply {
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            }

            val titleCluster = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            titleCluster.addView(overlaySourceBadgeText)
            titleCluster.addView(
                overlaySourceNameText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 18
                }
            )

            overlayStatusText = TextView(this).apply {
                setTextColor(palette.accentColor)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.END
            }

            headerRow.addView(
                titleCluster,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            headerRow.addView(overlayStatusText)

            val metricsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val fareMetric = createMetricColumn().also {
                overlayFareValueText = it.valueText
                overlayFareLabelText = it.labelText
            }
            val hourMetric = createMetricColumn().also {
                overlayHourValueText = it.valueText
                overlayHourLabelText = it.labelText
            }
            val kmMetric = createMetricColumn().also {
                overlayKmValueText = it.valueText
                overlayKmLabelText = it.labelText
            }

            metricsRow.addView(
                fareMetric.container,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            metricsRow.addView(
                hourMetric.container,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 18
                }
            )
            metricsRow.addView(
                kmMetric.container,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 18
                }
            )

            overlayCompactSummaryText = TextView(this).apply {
                setPadding(18, 14, 18, 14)
                setTextColor(Color.parseColor("#D1D5DB"))
                textSize = 13f
                setLineSpacing(2f, 1f)
                background = roundedBackground(Color.parseColor("#2A2A2A"), Color.parseColor("#2A2A2A"), 16f, 0)
            }

            val pickupSection = createLegSection("P").also {
                overlayPickupLegText = it.titleText
                overlayPickupAddressText = it.bodyText
            }
            val dropSection = createLegSection("D").also {
                overlayDropLegText = it.titleText
                overlayDropAddressText = it.bodyText
            }

            overlayDecisionText = TextView(this).apply {
                setTextColor(palette.accentColor)
                textSize = 15f
                setLineSpacing(3f, 1f)
            }

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            overlayRejectButton = Button(this).apply {
                text = "Recusar"
                isAllCaps = false
                setTextColor(Color.parseColor("#111827"))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 14f
                background = roundedBackground(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), 18f, 0)
                setOnClickListener { performManualAction(false) }
            }

            overlayAcceptButton = Button(this).apply {
                text = "Aceitar"
                isAllCaps = false
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                textSize = 14f
                background = horizontalGradientBackground(
                    intArrayOf(Color.parseColor("#FF8A24"), Color.parseColor("#FF5B8C")),
                    18f,
                    palette.strokeColor
                )
                setOnClickListener { performManualAction(true) }
            }

            container.addView(headerRow)
            container.addView(
                metricsRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 24
                }
            )
            container.addView(
                overlayCompactSummaryText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 18
                }
            )
            container.addView(
                pickupSection.container,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 20
                }
            )
            container.addView(
                dropSection.container,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 18
                }
            )
            container.addView(
                overlayDecisionText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 20
                    bottomMargin = 18
                }
            )
            actionsRow.addView(
                overlayRejectButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            actionsRow.addView(
                overlayAcceptButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 16
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
            )
            overlayLayoutParams = params
            applyOverlayLayoutSettings(params, settings)

            try {
                windowManager?.addView(container, params)
                overlayView = container
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to show overlay", error)
                return
            }
        }

        overlayLayoutParams?.let { params ->
            applyOverlayLayoutSettings(params, settings)
            overlayView?.let { windowManager?.updateViewLayout(it, params) }
        }
        val primaryTextColor = settings.cardTheme.primaryTextColor()
        val secondaryTextColor = settings.cardTheme.secondaryTextColor()
        val summaryFill = settings.cardTheme.summaryFillColor()
        overlayView?.background = roundedBackground(palette.surfaceColor, palette.strokeColor, 34f, 4)
        overlayView?.alpha = settings.opacityPercent / 100f
        overlayView?.setOnClickListener(
            if (settings.tapToClose) {
                {
                    dismissedOverlayFingerprint = currentOverlayFingerprint
                    hideOverlay()
                }
            } else {
                null
            }
        )
        overlaySourceNameText?.setTextColor(primaryTextColor)
        overlayStatusText?.setTextColor(palette.accentColor)
        overlayFareValueText?.setTextColor(primaryTextColor)
        overlayFareLabelText?.setTextColor(secondaryTextColor)
        // EUR/h — cor conforme o limiar das definicoes
        val eurPerHourVal = offer.eurPerHour()
        val hourColor = when {
            eurPerHourVal == null -> primaryTextColor
            eurPerHourVal >= settings.goodEurPerHour -> Color.parseColor("#22C55E")   // verde
            eurPerHourVal >= settings.mediumEurPerHour -> Color.parseColor("#F59E0B") // amarelo
            else -> Color.parseColor("#FF5F63")                                        // vermelho
        }
        overlayHourValueText?.setTextColor(hourColor)
        overlayHourLabelText?.setTextColor(secondaryTextColor)
        // EUR/km — cor conforme o limiar das definicoes
        val eurPerKmVal = offer.eurPerKm()
        val kmColor = when {
            eurPerKmVal == null -> primaryTextColor
            eurPerKmVal >= settings.goodEurPerKm -> Color.parseColor("#22C55E")   // verde
            eurPerKmVal >= settings.mediumEurPerKm -> Color.parseColor("#F59E0B") // amarelo
            else -> Color.parseColor("#FF5F63")                                    // vermelho
        }
        overlayKmValueText?.setTextColor(kmColor)
        overlayKmLabelText?.setTextColor(secondaryTextColor)
        overlayCompactSummaryText?.setTextColor(secondaryTextColor)
        overlayCompactSummaryText?.background = roundedBackground(summaryFill, summaryFill, 16f, 0)
        overlayPickupLegText?.setTextColor(primaryTextColor)
        overlayPickupAddressText?.setTextColor(secondaryTextColor)
        overlayDropLegText?.setTextColor(primaryTextColor)
        overlayDropAddressText?.setTextColor(secondaryTextColor)
        overlayDecisionText?.setTextColor(secondaryTextColor)
        // Badge: fundo da cor de acento da decisão (VERDE/AMARELO/VERMELHO), texto branco ou escuro
        val badgeTextColor = when (settings.cardTheme) {
            CardTheme.BLACK, CardTheme.COLOR -> Color.WHITE
            CardTheme.WHITE -> Color.parseColor("#111827")
        }
        overlaySourceBadgeText?.setTextColor(badgeTextColor)
        overlaySourceBadgeText?.background = roundedBackground(palette.strokeColor, palette.strokeColor, 10f, 0)

        val sourceLine = offer.sourceApp.displayName()
        // Nome da viagem: muda apenas quando a viagem e diferente (fingerprint novo)
        val fingerprint = offer.fingerprint()
        if (fingerprint != lastStatusNameFingerprint) {
            currentTripStatusName = overlayStatusTitle(offer, result)
            lastStatusNameFingerprint = fingerprint
            saveDebugScreenshot(offer.sourceApp.name)
        }
        val statusLine = currentTripStatusName ?: overlayStatusTitle(offer, result)
        val fareLine = offer.fareEur?.let { "${it.money()} EUR" } ?: "-- EUR"
        val pickupKm = offer.pickupDistanceKm?.km() ?: "--"
        val tripKm = offer.tripDistanceKm?.km() ?: "--"
        val totalKm = offer.totalDistanceKm()?.km() ?: "--"
        val pickupMin = offer.pickupDurationMin?.minutes() ?: "--"
        val tripMin = offer.tripDurationMin?.minutes() ?: "--"
        val totalMin = offer.totalDurationMin()?.minutes() ?: "--"
        val compactSummary =
            "$pickupKm km pickup | $totalKm km total | $totalMin min total | recolha ${offer.pickupPostalCode ?: "----"} | destino ${offer.destinationPostalCode ?: "----"}"
        val pickupLeg = "$pickupMin min - $pickupKm km"
        val dropLeg = "$tripMin min - $tripKm km"
        val pickupAddress = offer.pickupAddress?.normalizeOverlayLine() ?: "Local de recolha nao lido"
        val dropAddress = offer.destinationAddress?.normalizeOverlayLine() ?: "Destino nao lido"
        val decisionLine =
            result.reasons.take(4).joinToString(" | ").ifBlank { palette.fallbackReason }
        val scale = settings.fontScale.multiplier

        overlaySourceBadgeText?.text = offer.sourceApp.shortBadge()
        overlaySourceNameText?.text = sourceLine
        overlayStatusText?.text = statusLine
        overlayFareValueText?.text = fareLine
        overlayFareLabelText?.text = "tarifa total"
        overlayHourValueText?.text = "${offer.eurPerHour()?.money() ?: "--"} EUR/h"
        overlayHourLabelText?.text = "por hora"
        overlayKmValueText?.text = "${offer.eurPerKm()?.money() ?: "--"} EUR/km"
        overlayKmLabelText?.text = "por km"
        overlayCompactSummaryText?.text = compactSummary
        overlayPickupLegText?.text = "P  $pickupLeg"
        overlayPickupAddressText?.text = pickupAddress
        overlayDropLegText?.text = "D  $dropLeg"
        overlayDropAddressText?.text = dropAddress
        overlayDecisionText?.text = decisionLine

        overlaySourceBadgeText?.textSize = 14f * scale
        overlaySourceNameText?.textSize = 20f * scale
        overlayStatusText?.textSize = 16f * scale
        overlayFareValueText?.textSize = 20f * scale
        overlayFareLabelText?.textSize = 12f * scale
        overlayHourValueText?.textSize = 20f * scale
        overlayHourLabelText?.textSize = 12f * scale
        overlayKmValueText?.textSize = 20f * scale
        overlayKmLabelText?.textSize = 12f * scale
        overlayCompactSummaryText?.textSize = 13f * scale
        overlayPickupLegText?.textSize = 17f * scale
        overlayPickupAddressText?.textSize = 13f * scale
        overlayDropLegText?.textSize = 17f * scale
        overlayDropAddressText?.textSize = 13f * scale
        overlayDecisionText?.textSize = 15f * scale

        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, settings.durationSeconds * 1000L)
    }

    private fun applyOverlayLayoutSettings(
        params: WindowManager.LayoutParams,
        settings: TripGuardSettings
    ) {
        val gravityHorizontal = when (settings.horizontalPosition) {
            HorizontalPosition.LEFT -> Gravity.START
            HorizontalPosition.CENTER -> Gravity.CENTER_HORIZONTAL
            HorizontalPosition.RIGHT -> Gravity.END
        }
        val gravityVertical = when (settings.verticalPosition) {
            VerticalPosition.TOP -> Gravity.TOP
            VerticalPosition.CENTER -> Gravity.CENTER_VERTICAL
            VerticalPosition.BOTTOM -> Gravity.BOTTOM
        }
        params.gravity = gravityHorizontal or gravityVertical
        params.width = resources.displayMetrics.widthPixels - 96
        params.x = 0
        params.y = when (settings.verticalPosition) {
            VerticalPosition.TOP -> 64
            VerticalPosition.CENTER -> 0
            VerticalPosition.BOTTOM -> 64
        }
    }

    private fun OfferDecision.toOverlayPalette(theme: CardTheme): OverlayPalette =
        when (this) {
            OfferDecision.REJECT -> OverlayPalette(
                fallbackReason = "Abaixo dos filtros definidos",
                surfaceColor = theme.surfaceColorFor(
                    dark = "#121212",
                    light = "#FFFFFF",
                    tinted = "#FFE4E6"
                ),
                strokeColor = Color.parseColor("#FF5F63"),
                accentColor = Color.parseColor("#FF6B78")
            )
            OfferDecision.REVIEW -> OverlayPalette(
                fallbackReason = "Faltam dados para decidir com seguranca",
                surfaceColor = theme.surfaceColorFor(
                    dark = "#121212",
                    light = "#FFFFFF",
                    tinted = "#FFF7D6"
                ),
                strokeColor = Color.parseColor("#F59E0B"),
                accentColor = Color.parseColor("#FBBF24")
            )
            OfferDecision.ACCEPT -> OverlayPalette(
                fallbackReason = "Oferta cumpre os filtros base",
                surfaceColor = theme.surfaceColorFor(
                    dark = "#121212",
                    light = "#FFFFFF",
                    tinted = "#E8FFF1"
                ),
                strokeColor = Color.parseColor("#22C55E"),
                accentColor = Color.parseColor("#4ADE80")
            )
        }

    private fun overlayStatusTitle(offer: TripOffer, result: EvaluationResult): String =
        when (result.decision) {
            OfferDecision.REJECT -> REJECT_NAMES.random()
            OfferDecision.REVIEW -> REVIEW_NAMES.random()
            OfferDecision.ACCEPT -> ACCEPT_NAMES.random()
        }

    private fun createMetricColumn(): MetricColumnViews {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val valueText = TextView(this).apply {
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val labelText = TextView(this).apply {
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 12f
        }
        container.addView(valueText)
        container.addView(
            labelText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4
            }
        )
        return MetricColumnViews(container, valueText, labelText)
    }

    private fun createLegSection(prefix: String): LegSectionViews {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleText = TextView(this).apply {
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        val bodyText = TextView(this).apply {
            setTextColor(Color.parseColor("#D4D4D8"))
            textSize = 13f
            setLineSpacing(2f, 1f)
        }
        container.addView(titleText)
        container.addView(
            bodyText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4
                marginStart = 34
            }
        )
        return LegSectionViews(container, titleText, bodyText)
    }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int,
        cornerRadius: Float,
        strokeWidth: Int
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            setCornerRadius(cornerRadius)
            if (strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }

    private fun horizontalGradientBackground(
        colors: IntArray,
        cornerRadius: Float,
        strokeColor: Int
    ): Drawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            setCornerRadius(cornerRadius)
            setStroke(2, strokeColor)
        }

    private fun SourceApp.displayName(): String =
        when (this) {
            SourceApp.UBER -> "Uber"
            SourceApp.BOLT -> "Bolt"
            SourceApp.UNKNOWN -> "App"
        }

    private fun SourceApp.shortBadge(): String =
        when (this) {
            SourceApp.UBER -> "UBER"
            SourceApp.BOLT -> "BOLT"
            SourceApp.UNKNOWN -> "APP"
        }

    private fun String.normalizeOverlayLine(): String =
        replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun CardTheme.surfaceColorFor(dark: String, light: String, tinted: String): Int =
        when (this) {
            CardTheme.BLACK -> Color.parseColor(dark)
            CardTheme.WHITE -> Color.parseColor(light)
            CardTheme.COLOR -> Color.parseColor(tinted)
        }

    private fun CardTheme.primaryTextColor(): Int =
        when (this) {
            CardTheme.BLACK -> Color.parseColor("#FFFFFF")
            CardTheme.WHITE, CardTheme.COLOR -> Color.parseColor("#111827")
        }

    private fun CardTheme.secondaryTextColor(): Int =
        when (this) {
            CardTheme.BLACK -> Color.parseColor("#D4D4D8")
            CardTheme.WHITE, CardTheme.COLOR -> Color.parseColor("#475467")
        }

    private fun CardTheme.summaryFillColor(): Int =
        when (this) {
            CardTheme.BLACK -> Color.parseColor("#2A2A2A")
            CardTheme.WHITE -> Color.parseColor("#F3F4F6")
            CardTheme.COLOR -> Color.parseColor("#FFFFFF")
        }

    private fun Double.money(): String = String.format(Locale.US, "%.2f", this)

    private fun Double.km(): String = String.format(Locale.US, "%.1f", this)

    private fun Double.minutes(): String = String.format(Locale.US, "%.0f", this)

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

        dismissedOverlayFingerprint = currentOverlayFingerprint
        hideOverlay()
    }

    private fun hideOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to remove overlay", error)
            }
        }
        overlayView = null
        overlaySourceBadgeText = null
        overlaySourceNameText = null
        overlayStatusText = null
        overlayFareValueText = null
        overlayFareLabelText = null
        overlayHourValueText = null
        overlayHourLabelText = null
        overlayKmValueText = null
        overlayKmLabelText = null
        overlayCompactSummaryText = null
        overlayPickupLegText = null
        overlayPickupAddressText = null
        overlayDropLegText = null
        overlayDropAddressText = null
        overlayDecisionText = null
        overlayAcceptButton = null
        overlayRejectButton = null
    }

    private fun saveDebugScreenshot(label: String, customBitmap: Bitmap? = null) {
        val now = System.currentTimeMillis()
        val dir = File(cacheDir, "screenshots").apply { mkdirs() }
        
        // Limpar prints antigos (manter os ultimos 15)
        val files = dir.listFiles()?.sortedBy { it.lastModified() }
        if (files != null && files.size > 15) {
            files.take(files.size - 15).forEach { it.delete() }
        }

        val file = File(dir, "tripguard-${label}-${now}.jpg")
        
        if (customBitmap != null) {
            runCatching {
                file.outputStream().use { out ->
                    customBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
            return
        }

        // Caso contrario, tirar screenshot via AccessibilityService
        try {
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
                        }.getOrNull() ?: return
                        runCatching {
                            file.outputStream().use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            }
                            bitmap.recycle()
                        }
                    }
                    override fun onFailure(errorCode: Int) {}
                }
            )
        } catch (_: Exception) {}
    }

    private fun clickBestMatch(keywords: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        return findAndClick(root, keywords)
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
            try {
                if (findAndClick(child, keywords)) {
                    return true
                }
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun AccessibilityEvent?.textSummary(): String =
        this?.text?.joinToString(" ").orEmpty()

    private fun String?.toSourceApp(): SourceApp =
        when {
            this == null -> SourceApp.UNKNOWN
            contains("ubercab", ignoreCase = true) ||
                contains("uber", ignoreCase = true) -> SourceApp.UBER
            contains("mtakso", ignoreCase = true) ||
                contains("bolt", ignoreCase = true) -> SourceApp.BOLT
            else -> SourceApp.UNKNOWN
        }

    private fun String?.isKnownDriverPackage(): Boolean = toSourceApp() != SourceApp.UNKNOWN

    private fun String.hasOfferSignal(): Boolean {
        val lower = lowercase()
        return "eur" in lower ||
            "\u20ac" in lower ||
            " km" in lower ||
            "uber" in lower ||
            "bolt" in lower ||
            "recolha" in lower ||
            "pickup" in lower ||
            "destino" in lower ||
            "dropoff" in lower
    }

    private fun List<String>.compactText(): String =
        asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(MAX_TEXT_CHARS)

    private fun String.fastTextFingerprint(): String =
        lowercase()
            .filterNot { it.isWhitespace() }
            .take(FINGERPRINT_CHARS)

    private fun appendCaptureDebug(
        packageName: String?,
        sourceHint: SourceApp,
        stage: String,
        detail: String,
        excerpt: String?
    ) {
        captureDebugStore.append(
            CaptureDebugEntry(
                timestampMs = System.currentTimeMillis(),
                packageName = packageName,
                sourceHint = sourceHint.name,
                stage = stage,
                detail = detail.take(420),
                excerpt = excerpt?.toDebugExcerpt()
            )
        )
    }

    private fun String.toDebugExcerpt(): String =
        lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .replace(Regex("""\b\d{4}-\d{3}\b"""), "####-###")
            .take(DEBUG_EXCERPT_CHARS)

    private fun TripOffer.fingerprint(): String =
        listOfNotNull(
            sourceApp.name,
            fareEur?.let { "%.2f".format(Locale.US, it) },
            pickupDistanceKm?.let { "%.1f".format(Locale.US, it) },
            tripDistanceKm?.let { "%.1f".format(Locale.US, it) },
            pickupPostalCode,
            destinationPostalCode
        ).joinToString("|").ifBlank { rawText.fastTextFingerprint() }

    private fun WindowCapture.confidenceFor(offer: TripOffer): Int {
        var score = 0
        if (sourceHint != SourceApp.UNKNOWN || offer.sourceApp != SourceApp.UNKNOWN) score += 3
        if (offer.fareEur != null) score += 4
        if (offer.pickupDistanceKm != null) score += 2
        if (offer.tripDistanceKm != null) score += 2
        if (offer.pickupDurationMin != null || offer.tripDurationMin != null) score += 1
        if (offer.pickupPostalCode != null || offer.destinationPostalCode != null) score += 1
        if (text.hasOfferSignal()) score += 1
        return max(score, 0)
    }

    private data class WindowCapture(
        val packageName: String?,
        val sourceHint: SourceApp,
        val text: String
    )

    private data class CapturedOffer(
        val offer: TripOffer,
        val confidence: Int,
        val fingerprint: String
    )

    private data class TraversalLimits(
        var nodesVisited: Int = 0
    )

    private data class OverlayPalette(
        val fallbackReason: String,
        val surfaceColor: Int,
        val strokeColor: Int,
        val accentColor: Int
    )

    private data class MetricColumnViews(
        val container: LinearLayout,
        val valueText: TextView,
        val labelText: TextView
    )

    private fun maybeAttemptUberScreenshotOcr(packageName: String, rawText: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (uberOcrInFlight && System.currentTimeMillis() - lastUberOcrAttemptMs > 1800L) {
            uberOcrInFlight = false
        }
        if (uberOcrInFlight) {
            return false
        }

        val normalizedTargetPackage = packageName.substringBefore("/")
        val visiblePackages = uberVisibleWindowPackages(normalizedTargetPackage)

        val hasVisibleUberWindow = hasVisibleUberWindow(normalizedTargetPackage)
        val onlyIgnoredForegrounds =
            visiblePackages.isNotEmpty() &&
                visiblePackages.all { isIgnoredForegroundPackage(it) }
        val allowBlindAttempt = shouldAttemptBlindUberOcr(rawText)
        if (!hasVisibleUberWindow && onlyIgnoredForegrounds && !allowBlindAttempt) {
            return false
        }
        if (!hasVisibleUberWindow && !allowBlindAttempt) {
            return false
        }

        val now = System.currentTimeMillis()
        val signature = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(12)
            .joinToString("|")
            .take(240)
        if (signature.isNotBlank() && signature == lastUberOcrSignature && now - lastUberOcrAttemptMs < 450L) {
            return false
        }
        if (now - lastUberOcrAttemptMs < 450L) {
            return false
        }
        // Nao relancar OCR para o mesmo ecra que ja foi mostrado e dispensado pelo timer
        if (signature.isNotBlank() && signature == dismissedOcrSignature) {
            return false
        }

        lastUberOcrAttemptMs = now
        lastUberOcrSignature = signature
        uberOcrInFlight = true

        try {
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
                            return
                        }

                        runUberOcr(packageName, cropUberScreenshot(bitmap))
                    }

                    override fun onFailure(errorCode: Int) {
                        uberOcrInFlight = false
                    }
                }
            )
        } catch (e: SecurityException) {
            uberOcrInFlight = false
            Log.e("TripGuardService", "takeScreenshot sem permissao: ${e.message}")
            return false
        } catch (e: Exception) {
            uberOcrInFlight = false
            Log.e("TripGuardService", "takeScreenshot erro: ${e.message}")
            return false
        }
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

                if (ocrText.isBlank()) {
                    return@addOnSuccessListener
                }

                val offer = TripOfferParser.parse(ocrText, SourceApp.UBER) ?: return@addOnSuccessListener
                
                // Nao re-exibe se o cartao desta oferta ja foi ocultado/dispensado
                val fingerprint = offer.fingerprint()
                if (fingerprint == dismissedOverlayFingerprint) {
                    return@addOnSuccessListener
                }

                val settings = settingsStore.read()
                val resultDecision = TripFilterEngine.evaluate(offer, settings.toFilterConfig())
                
                historyStore.save(
                    TripHistoryEntry(
                        capturedAtMs = System.currentTimeMillis(),
                        offer = offer.copy(rawText = "OCR_UBER\n${offer.rawText}"),
                        result = resultDecision
                    )
                )

                lastOverlayShownAtMs = System.currentTimeMillis()
                writeLastSummaries(offer, resultDecision)
                showOrUpdateOverlay(offer, resultDecision, settings)
            }
            .addOnFailureListener { error ->
                Log.e("TripGuardService", "ML Kit OCR failed: ${error.message}")
            }
            .addOnCompleteListener {
                uberOcrInFlight = false
                recognizer.close()
                bitmap.recycle()
            }
    }

    private fun shouldAttemptBlindUberOcr(rawText: String): Boolean {
        val normalized = rawText.lowercase()
        return (normalized.contains("com.ubercab") || normalized.contains("uber")) && 
            (normalized.contains("min") || normalized.contains("km"))
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

    private fun uberVisibleWindowPackages(targetPackage: String): List<String> {
        val packages = windows?.mapNotNull { window ->
            window.root?.packageName?.toString()
        }?.map { it.ifBlank { "unknown" } } ?: emptyList()
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

    private data class LegSectionViews(
        val container: LinearLayout,
        val titleText: TextView,
        val bodyText: TextView
    )

    companion object {
        private const val TAG = "TripGuardService"
        private const val MIN_PROCESS_INTERVAL_MS = 140L
        private const val OFFER_DEDUPE_WINDOW_MS = 12_000L
        private const val MAX_NODE_COUNT = 180
        private const val MAX_TEXT_CHARS = 12_000
        private const val FINGERPRINT_CHARS = 900
        private const val DEBUG_EXCERPT_CHARS = 240
        private const val MIN_CAPTURE_CONFIDENCE = 5

        // --- Nomes aleatórios por categoria ---
        val REJECT_NAMES = listOf(
            "Viagem de Merda",
            "Corrida do Inferno",
            "Nunca Mais",
            "Foge Daqui",
            "Péssima Viagem",
            "Rota do Caos",
            "Táxi do Desastre",
            "Corrida Maldita",
            "Viagem Azeda",
            "Destino Incerto",
            "Atraso Garantido",
            "Sem Esperança",
            "Mau Caminho",
            "Buraco Negro",
            "Falha Total",
            "Que Chatice",
            "Pior Não Dá",
            "Vai a Pé",
            "Nem Pensar",
            "Fiasco Total",
            "Que Raiva",
            "Mais Vale Andar",
            "Trânsito Infernal",
            "Caos Sobre Rodas",
            "Zero Estrelas"
        )

        val REVIEW_NAMES = listOf(
            "Viagem Complicada",
            "Corrida Falhada",
            "Destino Incerto",
            "Viagem Sem Graça",
            "Nem Sempre Corre Bem",
            "Quase Perfeita",
            "Entre Solavancos",
            "Rota Duvidosa",
            "Caminho Difícil",
            "Corrida Atribulada",
            "Serviço Mediano",
            "Viagem Morna",
            "Nada de Especial",
            "Mais ou Menos",
            "Assim Assim",
            "Sem Emoção",
            "Nem Boa Nem Má",
            "Vale o Que Vale",
            "À Rasca",
            "Vai Andando",
            "Corrida Cansativa",
            "Trânsito Sem Fim",
            "Tempo Perdido",
            "Paciência ao Volante",
            "Espera Eterna",
            "Percurso Complicado",
            "Nem Sempre Fácil",
            "Um Dia de Cada Vez",
            "A Caminho"
        )

        val ACCEPT_NAMES = listOf(
            "Viagem Excelente",
            "Viagem Perfeita",
            "Viagem Cinco Estrelas",
            "Viagem Top",
            "Viagem Premium",
            "Viagem Confortável",
            "Viagem Tranquila",
            "Viagem Segura",
            "Viagem Rápida",
            "Viagem Suave",
            "Viagem Impecável",
            "Viagem Sem Stress",
            "Viagem Espetacular",
            "Viagem Inesquecível",
            "Viagem Exemplar",
            "Serviço Excelente"
        )
    }
}
