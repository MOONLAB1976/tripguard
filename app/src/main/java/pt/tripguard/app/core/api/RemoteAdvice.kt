package pt.tripguard.app.core.api

data class RemoteAdvice(
    val summary: String,
    val riskLevel: String,
    val recommendations: List<String>,
    val receivedAtMs: Long
) {
    fun formatForUi(): String {
        val adviceLines = recommendations.ifEmpty { listOf("Sem recomendacoes remotas por agora.") }
        return buildString {
            appendLine("Conselho API: $summary")
            appendLine("Risco: $riskLevel")
            appendLine("Atualizado: $receivedAtMs")
            append(adviceLines.joinToString("\n"))
        }
    }
}
