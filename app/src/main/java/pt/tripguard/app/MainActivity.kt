package pt.tripguard.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import pt.tripguard.app.data.AppUpdateManager
import pt.tripguard.app.data.DiagnosticsExporter
import pt.tripguard.app.data.DiagnosticsSyncScheduler
import pt.tripguard.app.data.DiagnosticsStore
import pt.tripguard.app.data.TripHistoryStore
import pt.tripguard.app.databinding.ActivityMainBinding
import pt.tripguard.app.rules.FilterConfig
import pt.tripguard.app.rules.TripHistoryAnalyzer
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentPage: AppPage = AppPage.HOME
    private val prefs by lazy { getSharedPreferences("tripguard", MODE_PRIVATE) }
    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            installSelectedApk(uri)
        } else {
            Toast.makeText(this, "Nenhum APK escolhido", Toast.LENGTH_SHORT).show()
        }
    }
    private val pickExportFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                Toast.makeText(this, "Nenhuma pasta escolhida", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString(DiagnosticsExporter.KEY_EXPORT_FOLDER_URI, uri.toString()).apply()
                DiagnosticsSyncScheduler.ensureState(this)
                renderState()
                Toast.makeText(this, "Pasta ligada com sucesso", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Nao consegui guardar a pasta ligada", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        binding.toggleMonitoringButton.setOnClickListener {
            val next = !prefs.getBoolean("monitoring_enabled", true)
            prefs.edit().putBoolean("monitoring_enabled", next).apply()
            renderState()
        }

        binding.saveBlockedZonesButton.setOnClickListener {
            val cleaned = binding.blockedZonesInput.text?.toString().orEmpty()
                .split(",", ";", " ", "\n")
                .map { it.trim() }
                .filter { it.length >= 4 }
                .joinToString(",")
            prefs.edit()
                .putString("blocked_postal_prefixes", cleaned)
                .apply()
            Toast.makeText(this, "Codigos postais guardados", Toast.LENGTH_SHORT).show()
            renderState()
        }

        binding.refreshButton.setOnClickListener { renderState() }

        binding.overlayDurationMinusButton.setOnClickListener {
            updateOverlayDurationSeconds(-1)
        }

        binding.overlayDurationPlusButton.setOnClickListener {
            updateOverlayDurationSeconds(1)
        }

        binding.clearDiagnosticsButton.setOnClickListener {
            DiagnosticsStore(this).clear()
            TripHistoryStore(this).clear()
            prefs.edit()
                .remove("last_seen_package")
                .remove("last_raw_text_sample")
                .remove("last_event_debug")
                .remove("last_parse_debug")
                .remove("last_offer_summary")
                .remove("last_decision_summary")
                .remove("last_manual_action_summary")
                .apply()
            Toast.makeText(this, "Diagnostico e historico limpos", Toast.LENGTH_SHORT).show()
            renderState()
        }

        binding.shareDiagnosticsButton.setOnClickListener {
            shareDiagnostics()
        }

        binding.exportDownloadsButton.setOnClickListener {
            saveDiagnosticsToDownloads()
        }

        binding.exportAdbButton.setOnClickListener {
            saveDiagnosticsForCable()
        }

        binding.chooseExportFolderButton.setOnClickListener {
            pickExportFolderLauncher.launch(DiagnosticsExporter.loadLinkedExportFolderUri(this))
        }

        binding.exportDiagnosticsButton.setOnClickListener {
            exportDiagnosticsToLinkedFolder()
        }

        binding.toggleAutoExportButton.setOnClickListener {
            toggleAutoExport()
        }

        binding.checkOnlineUpdateButton.setOnClickListener {
            checkOnlineUpdate()
        }

        binding.installOnlineUpdateButton.setOnClickListener {
            installOnlineUpdate()
        }

        binding.installUpdateButton.setOnClickListener {
            pickApkLauncher.launch(arrayOf("*/*"))
        }

        binding.navHome.setOnClickListener { showPage(AppPage.HOME) }
        binding.navOffers.setOnClickListener { showPage(AppPage.OFFERS) }
        binding.navInsights.setOnClickListener { showPage(AppPage.INSIGHTS) }
        binding.navZones.setOnClickListener { showPage(AppPage.ZONES) }
        binding.navSettings.setOnClickListener { showPage(AppPage.SETTINGS) }

        applySystemInsets()
        showPage(AppPage.HOME)
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun renderState() {
        val blockedPrefixes = prefs.getString("blocked_postal_prefixes", null)
            ?.split(",", ";", " ", "\n")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        val config = if (blockedPrefixes.isEmpty()) {
            FilterConfig.default()
        } else {
            FilterConfig.default().copy(blockedPostalPrefixes = blockedPrefixes)
        }
        val history = TripHistoryStore(this).load()
        val analysis = TripHistoryAnalyzer.analyze(history)
        val blockedZones = config.blockedPostalPrefixes.joinToString(", ")
        val monitoringEnabled = prefs.getBoolean("monitoring_enabled", true)
        val lastOffer = prefs.getString("last_offer_summary", getString(R.string.no_offer_seen)).orEmpty()
        val lastDecision = prefs.getString("last_decision_summary", getString(R.string.no_decision_yet)).orEmpty()
        val lastManualAction = prefs.getString("last_manual_action_summary", getString(R.string.no_manual_action_yet)).orEmpty()
        val lastSeenPackage = prefs.getString("last_seen_package", "-").orEmpty()
        val lastRawSample = prefs.getString("last_raw_text_sample", "Sem leitura tecnica ainda.").orEmpty()
        val lastEventDebug = prefs.getString("last_event_debug", "Sem evento ainda.").orEmpty()
        val lastParseDebug = prefs.getString("last_parse_debug", "Sem parse ainda.").orEmpty()
        val diagnosticsLog = DiagnosticsStore(this).loadText().ifBlank { "Sem historico de diagnostico ainda." }
        val overlayEnabled = Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val linkedFolderName = DiagnosticsExporter.loadLinkedExportFolderName(this)
        val lastExportPath = prefs.getString(DiagnosticsExporter.KEY_LAST_EXPORT_PATH, null)
        val lastAutoExportAt = prefs.getString(DiagnosticsExporter.KEY_LAST_AUTO_EXPORT_AT, null)
        val autoExportEnabled = DiagnosticsSyncScheduler.isEnabled(this)
        val updateMetadataUrl = AppUpdateManager.loadMetadataUrl(this)
        val updateStatus = AppUpdateManager.loadStatus(this)

        binding.monitoringStatusText.text = if (monitoringEnabled) "Monitorizacao: Ligada" else "Monitorizacao: Desligada"
        binding.versionInfoText.text = "Versao ${BuildConfig.VERSION_NAME} | Build ${BuildConfig.BUILD_TIME}"
        binding.toggleMonitoringButton.text = if (monitoringEnabled) "Desligar leitura" else "Ligar leitura"
        binding.summaryText.text = getString(
            R.string.summary_template,
            blockedZones,
            config.minimumFareEur,
            config.minimumEurPerKm,
            config.minimumEurPerHour,
            config.maximumPickupKm
        )
        binding.lastOfferText.text = lastOffer
        binding.lastDecisionText.text = lastDecision
        binding.manualActionText.text = lastManualAction
        binding.analysisText.text = TripHistoryAnalyzer.formatForUi(analysis)
        binding.offersOverviewText.text = buildOffersOverview(history)
        binding.offersRecommendationText.text = buildOffersRecommendation(analysis)
        binding.insightsCompareText.text = buildCompareText(history)
        binding.insightsQualityText.text = buildQualityText(analysis)
        binding.insightsTimeText.text = buildTimeText(analysis)
        binding.insightsThresholdText.text = buildThresholdText(analysis)
        binding.overlayStatusText.text = "Overlay: ${if (overlayEnabled) "Ligado" else "Desligado"}"
        binding.accessibilityStatusText.text = "Acessibilidade: ${if (accessibilityEnabled) "Ligada" else "Desligada"}"
        binding.setupStepsText.text = "Se a Uber ou Bolt nao forem lidas, confirma overlay e acessibilidade antes do teste."
        binding.overlayDurationValueText.text = "${getOverlayDurationSeconds()} s"
        binding.exportFolderStatusText.text = if (linkedFolderName == null) {
            "Pasta ligada: nenhuma ainda"
        } else {
            "Pasta ligada: $linkedFolderName"
        }
        binding.exportPathStatusText.text = if (lastExportPath.isNullOrBlank()) {
            "Ultimo ficheiro guardado: nenhum ainda"
        } else {
            "Ultimo ficheiro guardado: $lastExportPath"
        }
        binding.autoExportStatusText.text = if (autoExportEnabled) {
            if (lastAutoExportAt.isNullOrBlank()) {
                "Exportacao automatica: ligada (cada 1 hora)"
            } else {
                "Exportacao automatica: ligada | ultimo envio $lastAutoExportAt"
            }
        } else {
            "Exportacao automatica: desligada"
        }
        binding.toggleAutoExportButton.text = if (autoExportEnabled) {
            "Desligar envio automatico por hora"
        } else {
            "Ligar envio automatico por hora"
        }
        binding.updateStatusText.text = updateStatus
        if (binding.updateMetadataUrlInput.text?.toString().orEmpty() != updateMetadataUrl) {
            binding.updateMetadataUrlInput.setText(updateMetadataUrl)
        }
        binding.blockedZonesInput.setText(blockedZones)
        applyStatusStyle(binding.monitoringStatusText, monitoringEnabled)
        applyStatusStyle(binding.overlayStatusText, overlayEnabled)
        applyStatusStyle(binding.accessibilityStatusText, accessibilityEnabled)
        applyStatusStyle(binding.autoExportStatusText, autoExportEnabled)
        binding.diagnosticText.text =
            "Pacote: $lastSeenPackage\n" +
                "Evento: $lastEventDebug\n" +
                "Parse: $lastParseDebug\n\n" +
                "Ultimo texto lido:\n${lastRawSample.take(1200)}\n\n" +
                "Historico de diagnostico:\n$diagnosticsLog"

        binding.historyText.text = history.joinToString("\n\n") { entry ->
            val pickupPrefix = entry.offer.pickupPostalCode?.take(4) ?: "----"
            val destinationPrefix = entry.offer.destinationPostalCode?.take(4) ?: "----"
            val fare = entry.offer.fareEur?.let { String.format("%.2f", it) } ?: "?"
            val pickup = entry.offer.pickupDistanceKm?.let { String.format("%.1f", it) } ?: "?"
            val trip = entry.offer.tripDistanceKm?.let { String.format("%.1f", it) } ?: "?"
            val eurPerKm = entry.offer.eurPerKm()?.let { String.format("%.2f", it) } ?: "?"
            val eurPerHour = entry.offer.eurPerHour()?.let { String.format("%.2f", it) } ?: "?"
            "${entry.offer.sourceApp.name} | $fare EUR | $eurPerKm EUR/km | $eurPerHour EUR/h | pickup $pickup km | trip $trip km | $pickupPrefix -> $destinationPrefix | ${entry.result.decision}"
        }.ifBlank {
            getString(R.string.no_history_yet)
        }
    }

    private fun buildOffersOverview(history: List<pt.tripguard.app.rules.TripHistoryEntry>): String {
        if (history.isEmpty()) return "Ainda nao tens ofertas validas guardadas."
        val recent = history.take(10)
        val averageFare = recent.mapNotNull { it.offer.fareEur }.averageOrZero()
        val averagePickup = recent.mapNotNull { it.offer.pickupDistanceKm }.averageOrZero()
        val averageTrip = recent.mapNotNull { it.offer.tripDistanceKm }.averageOrZero()
        return "Ultimas ${recent.size} leituras\nTarifa media ${format2(averageFare)} EUR\nRecolha media ${format1(averagePickup)} km\nViagem media ${format1(averageTrip)} km"
    }

    private fun buildOffersRecommendation(analysis: pt.tripguard.app.rules.HistoryAnalysis): String =
        analysis.advisorNotes.ifEmpty { listOf("Sem recomendacoes novas por agora.") }.joinToString("\n")

    private fun buildCompareText(history: List<pt.tripguard.app.rules.TripHistoryEntry>): String {
        val recent = history.take(10)
        val uber = recent.filter { it.offer.sourceApp.name == "UBER" }
        val bolt = recent.filter { it.offer.sourceApp.name == "BOLT" }
        return "Uber: ${uber.size} propostas\nBolt: ${bolt.size} propostas\nComparacao baseada nas ultimas 10 leituras."
    }

    private fun buildQualityText(analysis: pt.tripguard.app.rules.HistoryAnalysis): String =
        "Aceites: ${analysis.acceptedOffers}\nRejeitadas: ${analysis.rejectedOffers}\nMedia EUR/km: ${format2(analysis.averageEurPerKm)}"

    private fun buildTimeText(analysis: pt.tripguard.app.rules.HistoryAnalysis): String =
        "Tarifa media: ${format2(analysis.averageFare)} EUR\nMedia EUR/h: ${format2(analysis.averageEurPerHour)}"

    private fun buildThresholdText(analysis: pt.tripguard.app.rules.HistoryAnalysis): String =
        "Melhor zona: ${analysis.bestPostalPrefix ?: "desconhecida"}\n" +
            "Pior zona: ${analysis.worstPostalPrefix ?: "desconhecida"}\n" +
            "Bloqueios sugeridos: ${analysis.suggestedBlockedPrefixes.ifEmpty { listOf("nenhum ainda") }.joinToString(", ")}"

    private fun getOverlayDurationSeconds(): Int =
        prefs.getInt(KEY_OVERLAY_DURATION_SECONDS, DEFAULT_OVERLAY_DURATION_SECONDS)
            .coerceIn(MIN_OVERLAY_DURATION_SECONDS, MAX_OVERLAY_DURATION_SECONDS)

    private fun updateOverlayDurationSeconds(delta: Int) {
        val next = (getOverlayDurationSeconds() + delta)
            .coerceIn(MIN_OVERLAY_DURATION_SECONDS, MAX_OVERLAY_DURATION_SECONDS)
        prefs.edit().putInt(KEY_OVERLAY_DURATION_SECONDS, next).apply()
        renderState()
        Toast.makeText(this, "Cartao visivel durante $next segundos", Toast.LENGTH_SHORT).show()
    }

    private fun shareDiagnostics() {
        runCatching {
            val linkedExportPath = DiagnosticsExporter.exportDiagnosticsToLinkedFolder(this)
            val fileText = DiagnosticsExporter.buildDiagnosticsReport(this)
            val exportDir = File(cacheDir, "shared_diagnostics").apply { mkdirs() }
            val fileName = DiagnosticsExporter.buildDiagnosticsFileName()
            val exportFile = File(exportDir, fileName)
            exportFile.writeText(fileText)
            DiagnosticsExporter.rememberExportPath(this, linkedExportPath ?: exportFile.absolutePath)
            renderState()

            val contentUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                exportFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "TripGuard diagnostico")
                putExtra(Intent.EXTRA_TEXT, "Segue o ficheiro de erros do TripGuard.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Partilhar erros"))
        }.onFailure {
            Toast.makeText(this, "Nao consegui partilhar os erros", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportDiagnosticsToLinkedFolder() {
        val linkedUri = DiagnosticsExporter.loadLinkedExportFolderUri(this)
        if (linkedUri == null) {
            Toast.makeText(this, "Escolhe primeiro a pasta ligada", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            val linkedPath = DiagnosticsExporter.exportDiagnosticsToLinkedFolder(this)
                ?: error("Nao consegui guardar na pasta ligada")
            DiagnosticsExporter.rememberExportPath(this, linkedPath)
            renderState()
            Toast.makeText(this, "Diagnostico guardado na pasta ligada", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Nao consegui guardar o diagnostico na pasta ligada", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveDiagnosticsToDownloads() {
        runCatching {
            val publicPath = DiagnosticsExporter.saveDiagnosticsToDownloads(this)
            DiagnosticsExporter.rememberExportPath(this, publicPath)
            renderState()
            Toast.makeText(this, "Diagnostico guardado em Downloads/TripGuard", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "Nao consegui guardar nos Downloads", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveDiagnosticsForCable() {
        runCatching {
            val targetPath = DiagnosticsExporter.saveDiagnosticsForCable(this)
            DiagnosticsExporter.rememberExportPath(this, targetPath)
            renderState()
            Toast.makeText(this, "Diagnostico guardado para cabo/ADB", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "Nao consegui guardar para cabo/ADB", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleAutoExport() {
        if (DiagnosticsExporter.loadLinkedExportFolderUri(this) == null) {
            Toast.makeText(this, "Escolhe primeiro a pasta ligada", Toast.LENGTH_SHORT).show()
            return
        }
        val next = !DiagnosticsSyncScheduler.isEnabled(this)
        DiagnosticsSyncScheduler.setEnabled(this, next)
        renderState()
        Toast.makeText(
            this,
            if (next) "Envio automatico por hora ligado" else "Envio automatico por hora desligado",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun installSelectedApk(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivity(settingsIntent)
            Toast.makeText(
                this,
                "Ativa a permissao para instalar apps e tenta outra vez.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        runCatching {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(installIntent, "Instalar atualizacao"))
        }.onFailure {
            Toast.makeText(this, "Nao consegui abrir o instalador do APK", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkOnlineUpdate() {
        val metadataUrl = binding.updateMetadataUrlInput.text?.toString().orEmpty().trim()
        if (metadataUrl.isBlank()) {
            Toast.makeText(this, "Indica a URL do tripguard-update.json", Toast.LENGTH_SHORT).show()
            return
        }

        AppUpdateManager.saveMetadataUrl(this, metadataUrl)
        AppUpdateManager.saveStatus(this, "Atualizacao online: a verificar...")
        renderState()

        Thread {
            val diagnostics = DiagnosticsStore(this)
            runCatching {
                val update = AppUpdateManager.fetchUpdateInfo(metadataUrl)
                AppUpdateManager.rememberUpdateInfo(this, update)
                val currentVersionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                val status = if (update.versionCode > currentVersionCode) {
                    buildString {
                        append("Atualizacao disponivel: ${update.versionName} (build ${update.versionCode})")
                        if (update.notes.isNotBlank()) append(" | ${update.notes}")
                    }
                } else {
                    "Ja tens a versao mais recente (${update.versionName})"
                }
                AppUpdateManager.saveStatus(this, status)
                diagnostics.append("UPDATE", status)
            }.onFailure { error ->
                val status = "Falhou a verificacao online: ${error.message ?: error.javaClass.simpleName}"
                AppUpdateManager.saveStatus(this, status)
                diagnostics.append("UPDATE", status)
            }

            runOnUiThread {
                renderState()
            }
        }.start()
    }

    private fun installOnlineUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivity(settingsIntent)
            Toast.makeText(
                this,
                "Ativa a permissao para instalar apps e tenta outra vez.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val metadataUrl = binding.updateMetadataUrlInput.text?.toString().orEmpty().trim()
        if (metadataUrl.isNotBlank()) {
            AppUpdateManager.saveMetadataUrl(this, metadataUrl)
        }

        val apkUrl = AppUpdateManager.loadLastApkUrl(this)
        if (apkUrl.isBlank()) {
            Toast.makeText(this, "Verifica primeiro a atualizacao online", Toast.LENGTH_SHORT).show()
            return
        }

        AppUpdateManager.saveStatus(this, "Atualizacao online: a descarregar APK...")
        renderState()

        Thread {
            val diagnostics = DiagnosticsStore(this)
            runCatching {
                val apkFile = AppUpdateManager.downloadApk(this, apkUrl)
                val status = "APK descarregado: ${apkFile.name}"
                AppUpdateManager.saveStatus(this, status)
                diagnostics.append("UPDATE", status)
                val apkUri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    apkFile
                )
                runOnUiThread {
                    installSelectedApk(apkUri)
                    renderState()
                }
            }.onFailure { error ->
                val status = "Falhou o download da atualizacao: ${error.message ?: error.javaClass.simpleName}"
                AppUpdateManager.saveStatus(this, status)
                diagnostics.append("UPDATE", status)
                runOnUiThread {
                    renderState()
                    Toast.makeText(this, "Nao consegui descarregar a atualizacao", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showPage(page: AppPage) {
        currentPage = page
        binding.pageHome.visibility = if (page == AppPage.HOME) View.VISIBLE else View.GONE
        binding.pageOffers.visibility = if (page == AppPage.OFFERS) View.VISIBLE else View.GONE
        binding.pageInsights.visibility = if (page == AppPage.INSIGHTS) View.VISIBLE else View.GONE
        binding.pageZones.visibility = if (page == AppPage.ZONES) View.VISIBLE else View.GONE
        binding.pageSettings.visibility = if (page == AppPage.SETTINGS) View.VISIBLE else View.GONE
        setNavSelected(binding.navHome, page == AppPage.HOME)
        setNavSelected(binding.navOffers, page == AppPage.OFFERS)
        setNavSelected(binding.navInsights, page == AppPage.INSIGHTS)
        setNavSelected(binding.navZones, page == AppPage.ZONES)
        setNavSelected(binding.navSettings, page == AppPage.SETTINGS)
    }

    private fun applySystemInsets() {
        val rootTop = binding.rootLayout.paddingTop
        val rootLeft = binding.rootLayout.paddingLeft
        val rootRight = binding.rootLayout.paddingRight
        val navBottom = binding.bottomNavBar.paddingBottom
        val navLeft = binding.bottomNavBar.paddingLeft
        val navRight = binding.bottomNavBar.paddingRight
        val navTop = binding.bottomNavBar.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.rootLayout.setPadding(
                rootLeft,
                rootTop + systemBars.top,
                rootRight,
                0
            )
            binding.bottomNavBar.setPadding(
                navLeft + systemBars.left,
                navTop,
                navRight + systemBars.right,
                navBottom + systemBars.bottom
            )
            binding.mainScrollView.setPadding(
                binding.mainScrollView.paddingLeft,
                binding.mainScrollView.paddingTop,
                binding.mainScrollView.paddingRight,
                systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.rootLayout)
    }

    private fun setNavSelected(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) Color.parseColor("#16A34A") else Color.parseColor("#64748B"))
    }

    private fun applyStatusStyle(view: TextView, enabled: Boolean) {
        view.setBackgroundResource(
            if (enabled) R.drawable.bg_status_on else R.drawable.bg_status_off
        )
        view.setTextColor(Color.WHITE)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val serviceId = "$packageName/${pt.tripguard.app.service.TripAccessibilityService::class.java.name}"
        return enabledServices.split(':').any { it.equals(serviceId, ignoreCase = true) }
    }

    private fun format1(value: Double): String = String.format("%.1f", value)

    private fun format2(value: Double): String = String.format("%.2f", value)

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private enum class AppPage {
        HOME,
        OFFERS,
        INSIGHTS,
        ZONES,
        SETTINGS
    }

    private companion object {
        private const val KEY_OVERLAY_DURATION_SECONDS = "overlay_duration_seconds"
        private const val DEFAULT_OVERLAY_DURATION_SECONDS = 4
        private const val MIN_OVERLAY_DURATION_SECONDS = 2
        private const val MAX_OVERLAY_DURATION_SECONDS = 15
    }
}
