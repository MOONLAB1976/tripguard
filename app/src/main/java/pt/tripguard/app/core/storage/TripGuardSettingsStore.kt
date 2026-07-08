package pt.tripguard.app.core.storage

import android.content.Context
import pt.tripguard.app.core.domain.FilterConfig
import pt.tripguard.app.core.domain.SourceApp

class TripGuardSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): TripGuardSettings =
        TripGuardSettings(
            cardTheme = prefs.getString(KEY_CARD_THEME, null).toEnumOrDefault(CardTheme.BLACK),
            horizontalPosition = prefs.getString(KEY_HORIZONTAL_POSITION, null)
                .toEnumOrDefault(HorizontalPosition.CENTER),
            verticalPosition = prefs.getString(KEY_VERTICAL_POSITION, null)
                .toEnumOrDefault(VerticalPosition.TOP),
            fontScale = prefs.getString(KEY_FONT_SCALE, null).toEnumOrDefault(FontScale.XL),
            opacityPercent = prefs.getInt(KEY_OPACITY_PERCENT, 100).coerceIn(55, 100),
            durationSeconds = prefs.getInt(KEY_DURATION_SECONDS, 5).coerceIn(3, 8),
            tapToClose = prefs.getBoolean(KEY_TAP_TO_CLOSE, false),
            tripGuardEnabled = prefs.getBoolean(KEY_TRIP_GUARD_ENABLED, true),
            uberEnabled = prefs.getBoolean(KEY_UBER_ENABLED, true),
            boltEnabled = prefs.getBoolean(KEY_BOLT_ENABLED, true),
            goodEurPerKm = prefs.getFloat(KEY_GOOD_EUR_PER_KM, 0.85f).toDouble(),
            mediumEurPerKm = prefs.getFloat(KEY_MEDIUM_EUR_PER_KM, 0.55f).toDouble(),
            goodEurPerHour = prefs.getFloat(KEY_GOOD_EUR_PER_HOUR, 15f).toDouble(),
            mediumEurPerHour = prefs.getFloat(KEY_MEDIUM_EUR_PER_HOUR, 10f).toDouble()
        )

    fun write(settings: TripGuardSettings) {
        prefs.edit()
            .putString(KEY_CARD_THEME, settings.cardTheme.name)
            .putString(KEY_HORIZONTAL_POSITION, settings.horizontalPosition.name)
            .putString(KEY_VERTICAL_POSITION, settings.verticalPosition.name)
            .putString(KEY_FONT_SCALE, settings.fontScale.name)
            .putInt(KEY_OPACITY_PERCENT, settings.opacityPercent)
            .putInt(KEY_DURATION_SECONDS, settings.durationSeconds)
            .putBoolean(KEY_TAP_TO_CLOSE, settings.tapToClose)
            .putBoolean(KEY_TRIP_GUARD_ENABLED, settings.tripGuardEnabled)
            .putBoolean(KEY_UBER_ENABLED, settings.uberEnabled)
            .putBoolean(KEY_BOLT_ENABLED, settings.boltEnabled)
            .putFloat(KEY_GOOD_EUR_PER_KM, settings.goodEurPerKm.toFloat())
            .putFloat(KEY_MEDIUM_EUR_PER_KM, settings.mediumEurPerKm.toFloat())
            .putFloat(KEY_GOOD_EUR_PER_HOUR, settings.goodEurPerHour.toFloat())
            .putFloat(KEY_MEDIUM_EUR_PER_HOUR, settings.mediumEurPerHour.toFloat())
            .apply()
    }

    fun update(transform: (TripGuardSettings) -> TripGuardSettings): TripGuardSettings {
        val updated = transform(read()).normalized()
        write(updated)
        return updated
    }

    private fun String?.toEnumOrDefault(defaultValue: CardTheme): CardTheme =
        try {
            if (this.isNullOrBlank()) defaultValue else CardTheme.valueOf(this)
        } catch (_: IllegalArgumentException) {
            defaultValue
        }

    private fun String?.toEnumOrDefault(defaultValue: HorizontalPosition): HorizontalPosition =
        try {
            if (this.isNullOrBlank()) defaultValue else HorizontalPosition.valueOf(this)
        } catch (_: IllegalArgumentException) {
            defaultValue
        }

    private fun String?.toEnumOrDefault(defaultValue: VerticalPosition): VerticalPosition =
        try {
            if (this.isNullOrBlank()) defaultValue else VerticalPosition.valueOf(this)
        } catch (_: IllegalArgumentException) {
            defaultValue
        }

    private fun String?.toEnumOrDefault(defaultValue: FontScale): FontScale =
        try {
            if (this.isNullOrBlank()) defaultValue else FontScale.valueOf(this)
        } catch (_: IllegalArgumentException) {
            defaultValue
        }

    companion object {
        private const val PREFS_NAME = "tripguard"
        private const val KEY_CARD_THEME = "indicator_card_theme"
        private const val KEY_HORIZONTAL_POSITION = "indicator_horizontal_position"
        private const val KEY_VERTICAL_POSITION = "indicator_vertical_position"
        private const val KEY_FONT_SCALE = "indicator_font_scale"
        private const val KEY_OPACITY_PERCENT = "indicator_opacity_percent"
        private const val KEY_DURATION_SECONDS = "indicator_duration_seconds"
        private const val KEY_TAP_TO_CLOSE = "indicator_tap_to_close"
        private const val KEY_TRIP_GUARD_ENABLED = "trip_guard_enabled"
        private const val KEY_UBER_ENABLED = "platform_uber_enabled"
        private const val KEY_BOLT_ENABLED = "platform_bolt_enabled"
        private const val KEY_GOOD_EUR_PER_KM = "threshold_good_eur_per_km"
        private const val KEY_MEDIUM_EUR_PER_KM = "threshold_medium_eur_per_km"
        private const val KEY_GOOD_EUR_PER_HOUR = "threshold_good_eur_per_hour"
        private const val KEY_MEDIUM_EUR_PER_HOUR = "threshold_medium_eur_per_hour"
    }
}

data class TripGuardSettings(
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
) {
    fun normalized(): TripGuardSettings {
        val safeMediumKm = mediumEurPerKm.coerceIn(0.20, 2.50)
        val safeGoodKm = goodEurPerKm.coerceIn(safeMediumKm + 0.05, 3.00)
        val safeMediumHour = mediumEurPerHour.coerceIn(5.0, 35.0)
        val safeGoodHour = goodEurPerHour.coerceIn(safeMediumHour + 1.0, 45.0)
        return copy(
            opacityPercent = opacityPercent.coerceIn(55, 100),
            durationSeconds = durationSeconds.coerceIn(3, 8),
            tripGuardEnabled = tripGuardEnabled,
            goodEurPerKm = safeGoodKm,
            mediumEurPerKm = safeMediumKm,
            goodEurPerHour = safeGoodHour,
            mediumEurPerHour = safeMediumHour
        )
    }

    fun isPlatformEnabled(sourceApp: SourceApp): Boolean =
        when (sourceApp) {
            SourceApp.UBER -> uberEnabled
            SourceApp.BOLT -> boltEnabled
            SourceApp.UNKNOWN -> true
        }

    fun toFilterConfig(): FilterConfig =
        FilterConfig(
            minimumFareEur = 0.0,
            minimumEurPerKm = mediumEurPerKm,
            minimumEurPerHour = mediumEurPerHour,
            goodEurPerKm = goodEurPerKm,
            goodEurPerHour = goodEurPerHour,
            maximumPickupKm = 99.0,
            maximumPickupDurationMin = 99.0,
            maximumTripKm = 999.0,
            blockedPostalPrefixes = setOf("4400", "4410", "4420", "4430", "4435", "4440"),
            blockedZoneKeywords = setOf(
                "vila nova de gaia",
                "gaia",
                "mafamude",
                "oliveira do douro",
                "canidelo"
            )
        )
}

enum class CardTheme {
    COLOR,
    WHITE,
    BLACK
}

enum class HorizontalPosition {
    LEFT,
    CENTER,
    RIGHT;

    fun move(delta: Int): HorizontalPosition {
        val values = entries
        val nextIndex = (ordinal + delta).coerceIn(0, values.lastIndex)
        return values[nextIndex]
    }
}

enum class VerticalPosition {
    TOP,
    CENTER,
    BOTTOM;

    fun move(delta: Int): VerticalPosition {
        val values = entries
        val nextIndex = (ordinal + delta).coerceIn(0, values.lastIndex)
        return values[nextIndex]
    }
}

enum class FontScale(val label: String, val multiplier: Float) {
    M("M", 0.92f),
    L("L", 1.0f),
    XL("XL", 1.12f);

    fun move(delta: Int): FontScale {
        val values = entries
        val nextIndex = (ordinal + delta).coerceIn(0, values.lastIndex)
        return values[nextIndex]
    }
}
