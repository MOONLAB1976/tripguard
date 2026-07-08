package pt.tripguard.app.menus.ui

import pt.tripguard.app.core.storage.CardTheme
import pt.tripguard.app.core.storage.FontScale
import pt.tripguard.app.core.storage.HorizontalPosition
import pt.tripguard.app.core.storage.VerticalPosition

data class MainScreenState(
    val lastOffer: String,
    val summary: String,
    val lastDecision: String,
    val lastManualAction: String,
    val accessibilityStatus: String,
    val overlayStatus: String,
    val analysis: String,
    val trustStatus: String,
    val apiAdviceStatus: String,
    val distributionStatus: String,
    val menuPreview: String,
    val history: String,
    val accessibilityEnabled: Boolean,
    val overlayEnabled: Boolean,
    val tripGuardEnabled: Boolean,
    val settings: SettingsUiState
)

data class SettingsUiState(
    val cardTheme: CardTheme,
    val horizontalPosition: HorizontalPosition,
    val verticalPosition: VerticalPosition,
    val fontScale: FontScale,
    val opacityPercent: Int,
    val durationSeconds: Int,
    val tapToClose: Boolean,
    val tripGuardEnabled: Boolean,
    val uberEnabled: Boolean,
    val boltEnabled: Boolean,
    val goodEurPerKm: Double,
    val mediumEurPerKm: Double,
    val goodEurPerHour: Double,
    val mediumEurPerHour: Double
)
