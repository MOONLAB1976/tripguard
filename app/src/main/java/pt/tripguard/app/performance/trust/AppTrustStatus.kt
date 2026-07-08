package pt.tripguard.app.performance.trust

data class AppTrustStatus(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val accessibilityEnabled: Boolean,
    val overlayEnabled: Boolean,
    val diagnosticsReady: Boolean,
    val updateChannel: String,
    val distributionReadiness: String
) {
    fun permissionSummary(): String {
        val accessibility = if (accessibilityEnabled) "ativa" else "por ativar"
        val overlay = if (overlayEnabled) "ativo" else "por ativar"
        val diagnostics = if (diagnosticsReady) "prontos" else "sem historico ainda"
        return "Acessibilidade: $accessibility\nOverlay: $overlay\nDiagnosticos: $diagnostics"
    }

    fun versionSummary(): String =
        "Versao instalada: $versionName ($versionCode)\nCanal de updates: $updateChannel\nPacote: $packageName"
}
