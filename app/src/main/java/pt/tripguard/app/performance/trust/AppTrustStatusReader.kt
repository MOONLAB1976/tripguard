package pt.tripguard.app.performance.trust

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import pt.tripguard.app.BuildConfig
import pt.tripguard.app.performance.capture.TripAccessibilityService
import pt.tripguard.app.core.storage.TripHistoryStore

class AppTrustStatusReader(
    private val context: Context
) {
    fun read(): AppTrustStatus {
        val history = TripHistoryStore(context).load()
        return AppTrustStatus(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            packageName = BuildConfig.APPLICATION_ID,
            accessibilityEnabled = isAccessibilityServiceEnabled(),
            overlayEnabled = Settings.canDrawOverlays(context),
            diagnosticsReady = history.isNotEmpty(),
            updateChannel = "manual/local por agora; Play Store quando a ficha estiver aprovada",
            distributionReadiness = buildDistributionReadiness(history.isNotEmpty())
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedServiceName = "${context.packageName}/${TripAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        return splitter.any { enabledService ->
            enabledService.equals(expectedServiceName, ignoreCase = true)
        }
    }

    private fun buildDistributionReadiness(hasHistory: Boolean): String {
        val checks = buildList {
            add("Release versionada para validacao interna.")
            add("Sem permissao de armazenamento; relatorios usam partilha segura por FileProvider.")
            add("Backup Android desativado para reduzir exposicao de historico local.")
            add("Permissoes sensiveis continuam a exigir justificacao clara: acessibilidade e overlay.")
            add("IA/API futura deve ficar opt-in, com endpoint configuravel e sem envio automatico de moradas.")
            if (hasHistory) {
                add("Historico recente disponivel para diagnostico.")
            } else {
                add("Capturar ofertas reais antes de submeter para testes externos.")
            }
            add("Dispositivo: Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}.")
        }
        return checks.joinToString("\n")
    }
}
