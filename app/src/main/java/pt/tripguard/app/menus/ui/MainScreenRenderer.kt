package pt.tripguard.app.menus.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import pt.tripguard.app.R
import pt.tripguard.app.databinding.ActivityMainBinding
import pt.tripguard.app.core.storage.CardTheme
import pt.tripguard.app.core.storage.FontScale
import pt.tripguard.app.core.storage.HorizontalPosition
import pt.tripguard.app.core.storage.VerticalPosition
import kotlin.math.max

object MainScreenRenderer {
    fun render(
        binding: ActivityMainBinding,
        state: MainScreenState
    ) {
        binding.lastOfferText.text = state.lastOffer
        binding.summaryText.text = state.summary
        binding.lastDecisionText.text = state.lastDecision
        binding.manualActionText.text = state.lastManualAction
        binding.analysisText.text = state.analysis
        binding.trustStatusText.text = state.trustStatus
        binding.apiAdviceStatusText.text = state.apiAdviceStatus
        binding.distributionStatusText.text = state.distributionStatus
        binding.historyText.text = state.history
        binding.accessibilityStatusText.text = binding.root.context.getString(
            if (state.accessibilityEnabled) R.string.accessibility_ready else R.string.accessibility_missing
        )
        binding.overlayStatusText.text = binding.root.context.getString(
            if (state.overlayEnabled) R.string.overlay_ready else R.string.overlay_missing
        )
        binding.tripGuardToggleButton.text = binding.root.context.getString(
            if (state.tripGuardEnabled) R.string.home_tripguard_toggle_on else R.string.home_tripguard_toggle_off
        )
        binding.accessibilityStatusText.setBackgroundResource(
            if (state.accessibilityEnabled) R.drawable.bg_permission_ready else R.drawable.bg_permission_missing
        )
        binding.overlayStatusText.setBackgroundResource(
            if (state.overlayEnabled) R.drawable.bg_permission_ready else R.drawable.bg_permission_missing
        )

        renderSettings(binding, state.settings)
    }

    private fun renderSettings(binding: ActivityMainBinding, settings: SettingsUiState) {
        applyThemeOptionCard(binding.themeColorOption, binding.themeColorSample, CardTheme.COLOR, settings.cardTheme)
        applyThemeOptionCard(binding.themeWhiteOption, binding.themeWhiteSample, CardTheme.WHITE, settings.cardTheme)
        applyThemeOptionCard(binding.themeBlackOption, binding.themeBlackSample, CardTheme.BLACK, settings.cardTheme)

        binding.horizontalValueText.text = settings.horizontalPosition.toDisplayLabel()
        binding.verticalValueText.text = settings.verticalPosition.toDisplayLabel()
        binding.fontValueText.text = settings.fontScale.label
        binding.opacityValueText.text = "${settings.opacityPercent}%"
        binding.durationValueText.text = "${settings.durationSeconds}s"
        binding.tapToCloseSwitch.isChecked = settings.tapToClose
        binding.uberPlatformSwitch.isChecked = settings.uberEnabled
        binding.boltPlatformSwitch.isChecked = settings.boltEnabled
        binding.goodKmValueText.text = settings.goodEurPerKm.eurPerKmText()
        binding.mediumKmValueText.text = settings.mediumEurPerKm.eurPerKmText()
        binding.goodHourValueText.text = settings.goodEurPerHour.eurPerHourText()
        binding.mediumHourValueText.text = settings.mediumEurPerHour.eurPerHourText()
        binding.badKmHintText.text = "Mau: abaixo de ${settings.mediumEurPerKm.eurPerKmText()}"
        binding.badHourHintText.text = "Mau: abaixo de ${settings.mediumEurPerHour.eurPerHourText()}"

        binding.uberPlatformBadge.background = pillDrawable(Color.parseColor("#050505"), Color.parseColor("#050505"))
        binding.boltPlatformBadge.background = pillDrawable(Color.parseColor("#2EAF6D"), Color.parseColor("#2EAF6D"))
        binding.goodKmThresholdCard.background = tintedCard(Color.parseColor("#DCFCE7"), Color.parseColor("#86EFAC"))
        binding.mediumKmThresholdCard.background = tintedCard(Color.parseColor("#FEF3C7"), Color.parseColor("#FCD34D"))
        binding.goodHourThresholdCard.background = tintedCard(Color.parseColor("#DCFCE7"), Color.parseColor("#86EFAC"))
        binding.mediumHourThresholdCard.background = tintedCard(Color.parseColor("#FEF3C7"), Color.parseColor("#FCD34D"))

        renderPreviewCard(
            dot = binding.previewGoodDot,
            card = binding.previewGoodCard,
            badge = binding.previewGoodBrandBadge,
            plusBadge = binding.previewGoodPlusBadge,
            fare = binding.previewGoodFareText,
            yield = binding.previewGoodYieldText,
            km = binding.previewGoodKmText,
            hour = binding.previewGoodHourText,
            meta = binding.previewGoodMetaText,
            warnings = binding.previewGoodWarningsText,
            settings = settings,
            category = PreviewCategory.GOOD
        )
        renderPreviewCard(
            dot = binding.previewMediumDot,
            card = binding.previewMediumCard,
            badge = binding.previewMediumBrandBadge,
            plusBadge = binding.previewMediumPlusBadge,
            fare = binding.previewMediumFareText,
            yield = binding.previewMediumYieldText,
            km = binding.previewMediumKmText,
            hour = binding.previewMediumHourText,
            meta = binding.previewMediumMetaText,
            warnings = binding.previewMediumWarningsText,
            settings = settings,
            category = PreviewCategory.MEDIUM
        )
        renderPreviewCard(
            dot = binding.previewBadDot,
            card = binding.previewBadCard,
            badge = binding.previewBadBrandBadge,
            plusBadge = binding.previewBadPlusBadge,
            fare = binding.previewBadFareText,
            yield = binding.previewBadYieldText,
            km = binding.previewBadKmText,
            hour = binding.previewBadHourText,
            meta = binding.previewBadMetaText,
            warnings = binding.previewBadWarningsText,
            settings = settings,
            category = PreviewCategory.BAD
        )
    }

    private fun applyThemeOptionCard(
        container: LinearLayout,
        sample: TextView,
        optionTheme: CardTheme,
        selectedTheme: CardTheme
    ) {
        val isSelected = optionTheme == selectedTheme
        container.background = roundedRect(
            fillColor = Color.WHITE,
            strokeColor = if (isSelected) Color.parseColor("#16A34A") else Color.parseColor("#E2E8F0"),
            cornerRadiusDp = 24f,
            strokeWidthDp = if (isSelected) 3 else 1
        )

        sample.background = when (optionTheme) {
            CardTheme.COLOR -> gradientRect(
                intArrayOf(Color.parseColor("#D1FAE5"), Color.parseColor("#22C55E")),
                22f,
                Color.parseColor("#86EFAC")
            )
            CardTheme.WHITE -> roundedRect(Color.WHITE, Color.parseColor("#16A34A"), 22f, 2)
            CardTheme.BLACK -> roundedRect(Color.parseColor("#222222"), Color.parseColor("#16A34A"), 22f, 2)
        }
    }

    private fun renderPreviewCard(
        dot: View,
        card: LinearLayout,
        badge: TextView,
        plusBadge: TextView,
        fare: TextView,
        yield: TextView,
        km: TextView,
        hour: TextView,
        meta: TextView,
        warnings: TextView,
        settings: SettingsUiState,
        category: PreviewCategory
    ) {
        val palette = category.paletteFor(settings.cardTheme)
        val sample = category.sampleValues(settings)

        dot.background = circleDrawable(palette.accentColor)
        card.background = previewCardBackground(settings.cardTheme, palette)
        badge.background = pillDrawable(
            if (settings.cardTheme == CardTheme.WHITE) Color.parseColor("#050505") else Color.parseColor("#050505"),
            if (settings.cardTheme == CardTheme.WHITE) Color.parseColor("#050505") else Color.parseColor("#050505")
        )
        plusBadge.background = roundedRect(
            fillColor = if (settings.cardTheme == CardTheme.WHITE) Color.WHITE else Color.parseColor("#F8FAFC"),
            strokeColor = if (settings.cardTheme == CardTheme.WHITE) Color.parseColor("#E5E7EB") else Color.parseColor("#E5E7EB"),
            cornerRadiusDp = 12f,
            strokeWidthDp = 1
        )

        fare.text = sample.fare
        yield.text = sample.yield
        km.text = sample.kmValue
        hour.text = sample.hourValue
        meta.text = sample.meta
        warnings.text = sample.warnings

        fare.setTextColor(palette.primaryTextColor)
        yield.setTextColor(palette.accentColor)
        km.setTextColor(palette.accentColor)
        hour.setTextColor(palette.accentColor)
        meta.setTextColor(palette.secondaryTextColor)
        warnings.setTextColor(palette.warningTextColor)

        val scale = settings.fontScale.multiplier
        fare.textSize = 22f * scale
        yield.textSize = 18f * scale
        km.textSize = 21f * scale
        hour.textSize = 21f * scale
        meta.textSize = 14f * scale
        warnings.textSize = 14f * scale
        card.alpha = settings.opacityPercent / 100f
    }

    private fun previewCardBackground(theme: CardTheme, palette: PreviewPalette): GradientDrawable =
        when (theme) {
            CardTheme.COLOR -> gradientRect(
                intArrayOf(palette.colorModeStart, palette.colorModeEnd),
                30f,
                palette.strokeColor
            )
            CardTheme.WHITE -> roundedRect(Color.WHITE, palette.strokeColor, 30f, 3)
            CardTheme.BLACK -> roundedRect(Color.parseColor("#232323"), palette.strokeColor, 30f, 3)
        }

    private fun roundedRect(
        fillColor: Int,
        strokeColor: Int,
        cornerRadiusDp: Float,
        strokeWidthDp: Int
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp
            setColor(fillColor)
            setStroke(strokeWidthDp, strokeColor)
        }

    private fun gradientRect(
        colors: IntArray,
        cornerRadiusDp: Float,
        strokeColor: Int
    ): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp
            setStroke(2, strokeColor)
        }

    private fun circleDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun pillDrawable(fillColor: Int, strokeColor: Int): GradientDrawable =
        roundedRect(fillColor, strokeColor, 14f, 1)

    private fun tintedCard(fillColor: Int, strokeColor: Int): GradientDrawable =
        roundedRect(fillColor, strokeColor, 24f, 1)

    private fun HorizontalPosition.toDisplayLabel(): String =
        when (this) {
            HorizontalPosition.LEFT -> "Esquerda"
            HorizontalPosition.CENTER -> "Centro"
            HorizontalPosition.RIGHT -> "Direita"
        }

    private fun VerticalPosition.toDisplayLabel(): String =
        when (this) {
            VerticalPosition.TOP -> "Em cima"
            VerticalPosition.CENTER -> "Centro"
            VerticalPosition.BOTTOM -> "Em baixo"
        }

    private fun Double.eurPerKmText(): String = String.format("%.2f EUR/km", this)

    private fun Double.eurPerHourText(): String = String.format("%.0f EUR/h", this)

    private enum class PreviewCategory {
        GOOD,
        MEDIUM,
        BAD;

        fun sampleValues(settings: SettingsUiState): PreviewSample {
            val fare = "7.50 EUR"
            return when (this) {
                GOOD -> PreviewSample(
                    fare = fare,
                    yield = "+32%",
                    kmValue = String.format("%.2f EUR", settings.goodEurPerKm),
                    hourValue = String.format("%.2f EUR", settings.goodEurPerHour),
                    meta = "12 min   8,5 km\n8,0 km pickup   +2,40 EUR lucro",
                    warnings = "Recolha 8,0 km\nDestino 22,0 km"
                )
                MEDIUM -> PreviewSample(
                    fare = fare,
                    yield = "+12%",
                    kmValue = String.format("%.2f EUR", settings.mediumEurPerKm),
                    hourValue = String.format("%.2f EUR", settings.mediumEurPerHour),
                    meta = "12 min   8,5 km\n8,0 km pickup   +1,10 EUR lucro",
                    warnings = "Recolha 8,0 km\nDestino 22,0 km"
                )
                BAD -> PreviewSample(
                    fare = fare,
                    yield = "-8%",
                    kmValue = String.format("%.2f EUR", max(0.10, settings.mediumEurPerKm - 0.12)),
                    hourValue = String.format("%.2f EUR", max(1.0, settings.mediumEurPerHour - 2.0)),
                    meta = "12 min   8,5 km\n8,0 km pickup   +0,30 EUR lucro",
                    warnings = "Recolha 8,0 km\nDestino 22,0 km"
                )
            }
        }

        fun paletteFor(theme: CardTheme): PreviewPalette =
            when (this) {
                GOOD -> PreviewPalette(
                    strokeColor = Color.parseColor("#16A34A"),
                    accentColor = Color.parseColor("#22C55E"),
                    primaryTextColor = if (theme == CardTheme.WHITE || theme == CardTheme.COLOR) Color.parseColor("#111827") else Color.WHITE,
                    secondaryTextColor = if (theme == CardTheme.WHITE || theme == CardTheme.COLOR) Color.parseColor("#374151") else Color.parseColor("#D4D4D8"),
                    warningTextColor = Color.parseColor("#D97706"),
                    colorModeStart = Color.parseColor("#E8FFF1"),
                    colorModeEnd = Color.parseColor("#B7F0C9")
                )
                MEDIUM -> PreviewPalette(
                    strokeColor = Color.parseColor("#D4A106"),
                    accentColor = Color.parseColor("#FBBF24"),
                    primaryTextColor = if (theme == CardTheme.WHITE || theme == CardTheme.COLOR) Color.parseColor("#111827") else Color.WHITE,
                    secondaryTextColor = if (theme == CardTheme.WHITE || theme == CardTheme.COLOR) Color.parseColor("#374151") else Color.parseColor("#D4D4D8"),
                    warningTextColor = Color.parseColor("#D97706"),
                    colorModeStart = Color.parseColor("#FFF9DB"),
                    colorModeEnd = Color.parseColor("#FDE68A")
                )
                BAD -> PreviewPalette(
                    strokeColor = Color.parseColor("#EF4444"),
                    accentColor = Color.parseColor("#F43F5E"),
                    primaryTextColor = if (theme == CardTheme.WHITE || theme == CardTheme.COLOR) Color.parseColor("#111827") else Color.WHITE,
                    secondaryTextColor = if (theme == CardTheme.WHITE || theme == CardTheme.COLOR) Color.parseColor("#374151") else Color.parseColor("#D4D4D8"),
                    warningTextColor = Color.parseColor("#EF4444"),
                    colorModeStart = Color.parseColor("#FFE4E6"),
                    colorModeEnd = Color.parseColor("#FECACA")
                )
            }
    }

    private data class PreviewSample(
        val fare: String,
        val yield: String,
        val kmValue: String,
        val hourValue: String,
        val meta: String,
        val warnings: String
    )

    private data class PreviewPalette(
        val strokeColor: Int,
        val accentColor: Int,
        val primaryTextColor: Int,
        val secondaryTextColor: Int,
        val warningTextColor: Int,
        val colorModeStart: Int,
        val colorModeEnd: Int
    )
}
