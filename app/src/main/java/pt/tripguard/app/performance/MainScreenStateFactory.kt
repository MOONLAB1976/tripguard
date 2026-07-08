package pt.tripguard.app.performance

import android.content.Context
import pt.tripguard.app.R
import pt.tripguard.app.core.api.RemoteAdviceStore
import pt.tripguard.app.core.api.TripAdviceApiConfig
import pt.tripguard.app.core.domain.TripAdvisorEngine
import pt.tripguard.app.core.storage.TripGuardSettingsStore
import pt.tripguard.app.core.storage.TripHistoryStore
import pt.tripguard.app.performance.trust.AppTrustStatusReader
import pt.tripguard.app.performance.trust.SyncIdentityStore
import pt.tripguard.app.menus.ui.MainScreenState
import pt.tripguard.app.menus.ui.SettingsUiState

class MainScreenStateFactory(
    private val context: Context
) {
    fun build(): MainScreenState {
        val prefs = context.getSharedPreferences("tripguard", Context.MODE_PRIVATE)
        val settings = TripGuardSettingsStore(context).read()
        val config = settings.toFilterConfig()
        val history = TripHistoryStore(context).load()
        val advisorReport = TripAdvisorEngine.advise(history, config)
        val trustStatus = AppTrustStatusReader(context).read()
        val syncIdentity = SyncIdentityStore(context).read()
        val apiConfig = TripAdviceApiConfig.read(context)
        val remoteAdviceStore = RemoteAdviceStore(context)
        val remoteAdvice = remoteAdviceStore.read()
        val blockedZones = config.blockedPostalPrefixes.joinToString(", ")
        val lastOffer = prefs.getString("last_offer_summary", context.getString(R.string.no_offer_seen)).orEmpty()
        val lastDecision = prefs.getString("last_decision_summary", context.getString(R.string.no_decision_yet)).orEmpty()
        val lastManualAction = prefs.getString("last_manual_action_summary", context.getString(R.string.no_manual_action_yet)).orEmpty()

        return MainScreenState(
            lastOffer = lastOffer,
            summary = context.getString(
                R.string.summary_template,
                blockedZones,
                config.minimumFareEur,
                config.minimumEurPerKm,
                config.minimumEurPerHour,
                config.maximumPickupKm,
                config.maximumPickupDurationMin
            ),
            lastDecision = lastDecision,
            lastManualAction = lastManualAction,
            accessibilityStatus = if (trustStatus.accessibilityEnabled) {
                context.getString(R.string.accessibility_ready)
            } else {
                context.getString(R.string.accessibility_missing)
            },
            overlayStatus = if (trustStatus.overlayEnabled) {
                context.getString(R.string.overlay_ready)
            } else {
                context.getString(R.string.overlay_missing)
            },
            analysis = TripAdvisorEngine.formatForUi(advisorReport),
            trustStatus = "${trustStatus.versionSummary()}\n\n${trustStatus.permissionSummary()}\n\nSync device: ${syncIdentity.deviceId}\nReferencia: ${syncIdentity.reference}",
            apiAdviceStatus = buildString {
                append(remoteAdviceStore.statusText(apiConfig))
                appendLine()
                appendLine()
                append(remoteAdvice?.formatForUi() ?: "Sem conselho API recebido ainda.")
            },
            distributionStatus = trustStatus.distributionReadiness,
            menuPreview = context.getString(R.string.menu_preview_text),
            history = history.joinToString("\n\n") { entry ->
                val pickupPrefix = entry.offer.pickupPostalCode?.take(4) ?: "----"
                val destinationPrefix = entry.offer.destinationPostalCode?.take(4) ?: "----"
                val fare = entry.offer.fareEur?.let { String.format("%.2f", it) } ?: "?"
                val pickup = entry.offer.pickupDistanceKm?.let { String.format("%.1f", it) } ?: "?"
                val trip = entry.offer.tripDistanceKm?.let { String.format("%.1f", it) } ?: "?"
                val eurPerKm = entry.offer.eurPerKm()?.let { String.format("%.2f", it) } ?: "?"
                val eurPerHour = entry.offer.eurPerHour()?.let { String.format("%.2f", it) } ?: "?"
                "${entry.offer.sourceApp.name} | $fare EUR | $eurPerKm EUR/km | $eurPerHour EUR/h | pickup $pickup km | trip $trip km | $pickupPrefix -> $destinationPrefix | ${entry.result.decision}"
            }.ifBlank {
                context.getString(R.string.no_history_yet)
            },
            accessibilityEnabled = trustStatus.accessibilityEnabled,
            overlayEnabled = trustStatus.overlayEnabled,
            tripGuardEnabled = settings.tripGuardEnabled,
            settings = SettingsUiState(
                cardTheme = settings.cardTheme,
                horizontalPosition = settings.horizontalPosition,
                verticalPosition = settings.verticalPosition,
                fontScale = settings.fontScale,
                opacityPercent = settings.opacityPercent,
                durationSeconds = settings.durationSeconds,
                tapToClose = settings.tapToClose,
                tripGuardEnabled = settings.tripGuardEnabled,
                uberEnabled = settings.uberEnabled,
                boltEnabled = settings.boltEnabled,
                goodEurPerKm = settings.goodEurPerKm,
                mediumEurPerKm = settings.mediumEurPerKm,
                goodEurPerHour = settings.goodEurPerHour,
                mediumEurPerHour = settings.mediumEurPerHour
            )
        )
    }
}
