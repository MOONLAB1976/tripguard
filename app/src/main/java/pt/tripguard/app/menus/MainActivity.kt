package pt.tripguard.app.menus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import pt.tripguard.app.core.api.TripAdviceScheduler
import pt.tripguard.app.databinding.ActivityMainBinding
import pt.tripguard.app.core.storage.CardTheme
import pt.tripguard.app.core.storage.TripGuardSettingsStore
import pt.tripguard.app.performance.trust.DiagnosticReportSharer
import pt.tripguard.app.menus.ui.MainScreenRenderer
import pt.tripguard.app.performance.MainScreenStateFactory

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsStore: TripGuardSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsStore = TripGuardSettingsStore(this)

        binding.openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.openOverlayButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        binding.refreshButton.setOnClickListener { renderState() }
        binding.shareDiagnosticsButton.setOnClickListener {
            startActivity(DiagnosticReportSharer(this).createShareIntent())
        }
        binding.navHome.setOnClickListener { showPage(MainPage.HOME) }
        binding.navOffers.setOnClickListener { showPage(MainPage.OFFERS) }
        binding.navInsights.setOnClickListener { showPage(MainPage.INSIGHTS) }
        binding.navZones.setOnClickListener { showPage(MainPage.ZONES) }
        binding.navSettings.setOnClickListener { showPage(MainPage.SETTINGS) }
        binding.homeQuickCardConfig.setOnClickListener { showPage(MainPage.SETTINGS) }
        binding.homeQuickCardOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        binding.homeQuickCardAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.homeQuickCardInsights.setOnClickListener { showPage(MainPage.INSIGHTS) }
        binding.tripGuardToggleButton.setOnClickListener {
            settingsStore.update { it.copy(tripGuardEnabled = !it.tripGuardEnabled) }
            renderState()
        }
        bindSubmenus()
        bindSettingsControls()

        TripAdviceScheduler.ensureScheduled(this)
        showPage(MainPage.HOME)
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun renderState() {
        val state = MainScreenStateFactory(this).build()
        MainScreenRenderer.render(binding, state)
    }

    private fun bindSettingsControls() {
        binding.themeColorOption.setOnClickListener {
            settingsStore.update { it.copy(cardTheme = CardTheme.COLOR) }
            renderState()
        }
        binding.themeWhiteOption.setOnClickListener {
            settingsStore.update { it.copy(cardTheme = CardTheme.WHITE) }
            renderState()
        }
        binding.themeBlackOption.setOnClickListener {
            settingsStore.update { it.copy(cardTheme = CardTheme.BLACK) }
            renderState()
        }

        binding.horizontalMinusButton.setOnClickListener {
            settingsStore.update { it.copy(horizontalPosition = it.horizontalPosition.move(-1)) }
            renderState()
        }
        binding.horizontalPlusButton.setOnClickListener {
            settingsStore.update { it.copy(horizontalPosition = it.horizontalPosition.move(1)) }
            renderState()
        }
        binding.verticalMinusButton.setOnClickListener {
            settingsStore.update { it.copy(verticalPosition = it.verticalPosition.move(-1)) }
            renderState()
        }
        binding.verticalPlusButton.setOnClickListener {
            settingsStore.update { it.copy(verticalPosition = it.verticalPosition.move(1)) }
            renderState()
        }
        binding.fontMinusButton.setOnClickListener {
            settingsStore.update { it.copy(fontScale = it.fontScale.move(-1)) }
            renderState()
        }
        binding.fontPlusButton.setOnClickListener {
            settingsStore.update { it.copy(fontScale = it.fontScale.move(1)) }
            renderState()
        }
        binding.opacityMinusButton.setOnClickListener {
            settingsStore.update { it.copy(opacityPercent = it.opacityPercent - 5) }
            renderState()
        }
        binding.opacityPlusButton.setOnClickListener {
            settingsStore.update { it.copy(opacityPercent = it.opacityPercent + 5) }
            renderState()
        }
        binding.durationMinusButton.setOnClickListener {
            settingsStore.update { it.copy(durationSeconds = it.durationSeconds - 1) }
            renderState()
        }
        binding.durationPlusButton.setOnClickListener {
            settingsStore.update { it.copy(durationSeconds = it.durationSeconds + 1) }
            renderState()
        }

        binding.tapToCloseSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.update { it.copy(tapToClose = isChecked) }
        }
        binding.uberPlatformSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.update { it.copy(uberEnabled = isChecked) }
            renderState()
        }
        binding.boltPlatformSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.update { it.copy(boltEnabled = isChecked) }
            renderState()
        }

        binding.goodKmMinusButton.setOnClickListener {
            settingsStore.update { it.copy(goodEurPerKm = it.goodEurPerKm - 0.05) }
            renderState()
        }
        binding.goodKmPlusButton.setOnClickListener {
            settingsStore.update { it.copy(goodEurPerKm = it.goodEurPerKm + 0.05) }
            renderState()
        }
        binding.mediumKmMinusButton.setOnClickListener {
            settingsStore.update { it.copy(mediumEurPerKm = it.mediumEurPerKm - 0.04) }
            renderState()
        }
        binding.mediumKmPlusButton.setOnClickListener {
            settingsStore.update { it.copy(mediumEurPerKm = it.mediumEurPerKm + 0.04) }
            renderState()
        }
        binding.goodHourMinusButton.setOnClickListener {
            settingsStore.update { it.copy(goodEurPerHour = it.goodEurPerHour - 1.0) }
            renderState()
        }
        binding.goodHourPlusButton.setOnClickListener {
            settingsStore.update { it.copy(goodEurPerHour = it.goodEurPerHour + 1.0) }
            renderState()
        }
        binding.mediumHourMinusButton.setOnClickListener {
            settingsStore.update { it.copy(mediumEurPerHour = it.mediumEurPerHour - 1.0) }
            renderState()
        }
        binding.mediumHourPlusButton.setOnClickListener {
            settingsStore.update { it.copy(mediumEurPerHour = it.mediumEurPerHour + 1.0) }
            renderState()
        }
    }

    private fun bindSubmenus() {
        binding.offersMenuHistory.setOnClickListener { scrollToSection(binding.offersSectionHistory) }
        binding.offersMenuGoals.setOnClickListener { scrollToSection(binding.offersSectionGoals) }
        binding.offersMenuActivity.setOnClickListener { scrollToSection(binding.offersSectionActivity) }
        binding.offersMenuShifts.setOnClickListener { scrollToSection(binding.offersSectionShifts) }

        binding.insightsMenuOverview.setOnClickListener { scrollToSection(binding.insightsSectionOverview) }
        binding.insightsMenuPlatforms.setOnClickListener { scrollToSection(binding.insightsSectionPlatforms) }
        binding.insightsMenuZones.setOnClickListener { scrollToSection(binding.insightsSectionZones) }
        binding.insightsMenuApi.setOnClickListener { scrollToSection(binding.insightsSectionApi) }

        binding.zonesMenuGarage.setOnClickListener { scrollToSection(binding.zonesSectionGarage) }
        binding.zonesMenuCosts.setOnClickListener { scrollToSection(binding.zonesSectionCosts) }
        binding.zonesMenuChecks.setOnClickListener { scrollToSection(binding.zonesSectionChecks) }
        binding.zonesMenuInsurance.setOnClickListener { scrollToSection(binding.zonesSectionInsurance) }

        binding.settingsMenuCard.setOnClickListener { scrollToSection(binding.settingsSectionCard) }
        binding.settingsMenuAnalysis.setOnClickListener { scrollToSection(binding.settingsSectionAnalysis) }
        binding.settingsMenuAlerts.setOnClickListener { scrollToSection(binding.settingsSectionAlerts) }
        binding.settingsMenuTools.setOnClickListener { scrollToSection(binding.settingsSectionTools) }
    }

    private fun scrollToSection(section: View) {
        binding.mainScroll.post {
            val y = section.top.coerceAtLeast(0)
            binding.mainScroll.smoothScrollTo(0, y)
        }
    }

    private fun showPage(page: MainPage) {
        binding.homePage.visibility = if (page == MainPage.HOME) View.VISIBLE else View.GONE
        binding.offersPage.visibility = if (page == MainPage.OFFERS) View.VISIBLE else View.GONE
        binding.insightsPage.visibility = if (page == MainPage.INSIGHTS) View.VISIBLE else View.GONE
        binding.zonesPage.visibility = if (page == MainPage.ZONES) View.VISIBLE else View.GONE
        binding.settingsPage.visibility = if (page == MainPage.SETTINGS) View.VISIBLE else View.GONE

        setSelectedNav(binding.navHome, page == MainPage.HOME)
        setSelectedNav(binding.navOffers, page == MainPage.OFFERS)
        setSelectedNav(binding.navInsights, page == MainPage.INSIGHTS)
        setSelectedNav(binding.navZones, page == MainPage.ZONES)
        setSelectedNav(binding.navSettings, page == MainPage.SETTINGS)
        binding.mainScroll.post { binding.mainScroll.smoothScrollTo(0, 0) }
    }

    private fun setSelectedNav(item: TextView, selected: Boolean) {
        item.isSelected = selected
    }

    private enum class MainPage {
        HOME,
        OFFERS,
        INSIGHTS,
        ZONES,
        SETTINGS
    }
}
