package pt.tripguard.app.performance.trust

import android.content.Context
import android.os.Build
import pt.tripguard.app.core.domain.TripHistoryEntry
import pt.tripguard.app.core.storage.CaptureDebugStore
import pt.tripguard.app.core.storage.TripHistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DiagnosticReportBuilder(
    private val context: Context
) {
    fun buildMarkdown(): String {
        val status = AppTrustStatusReader(context).read()
        val syncIdentity = SyncIdentityStore(context).read()
        val prefs = context.getSharedPreferences("tripguard", Context.MODE_PRIVATE)
        val history = TripHistoryStore(context).load()
        val captureDebug = CaptureDebugStore(context).load()
        val timestamp = utcTimestamp()

        return buildString {
            appendLine("# TripGuard diagnostics")
            appendLine()
            appendLine("Generated: $timestamp")
            appendLine("App: ${status.packageName}")
            appendLine("Version: ${status.versionName} (${status.versionCode})")
            appendLine("Sync-Device-Id: ${syncIdentity.deviceId}")
            appendLine("Sync-Device-Label: ${syncIdentity.deviceLabel}")
            appendLine("Sync-Reference: ${syncIdentity.reference}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            appendLine()
            appendLine("## Trust status")
            appendLine(status.permissionSummary())
            appendLine()
            appendLine("## Last app state")
            appendLine("Last offer: ${prefs.getString("last_offer_summary", "none")}")
            appendLine("Last decision: ${prefs.getString("last_decision_summary", "none")}")
            appendLine("Last manual action: ${prefs.getString("last_manual_action_summary", "none")}")
            appendLine()
            appendLine("## Capture inspection")
            if (captureDebug.isEmpty()) {
                appendLine("No technical capture events recorded yet.")
            } else {
                captureDebug.forEachIndexed { index, entry ->
                    appendLine("${index + 1}. ${entry.toDiagnosticLine()}")
                }
            }
            appendLine()
            appendLine("## Recent offers")
            if (history.isEmpty()) {
                appendLine("No captured offers yet.")
            } else {
                history.forEachIndexed { index, entry ->
                    appendLine("${index + 1}. ${entry.toSafeLine()}")
                }
            }
            appendLine()
            appendLine("## Privacy note")
            appendLine("This report avoids raw screen text and full addresses. It keeps only operational metrics, postal prefixes and decisions.")
            appendLine()
            appendLine("## Next checks before Play Store")
            appendLine("- Confirm accessibility disclosure text in the store listing and inside the app.")
            appendLine("- Confirm overlay use is manual-driver-assist, not hidden automation.")
            appendLine("- Test install/update path on a clean phone before public distribution.")
            appendLine("- Keep AI/API advice opt-in and redact personal trip details by default.")
        }
    }

    private fun TripHistoryEntry.toSafeLine(): String {
        val fare = offer.fareEur?.format2() ?: "?"
        val pickupKm = offer.pickupDistanceKm?.format1() ?: "?"
        val tripKm = offer.tripDistanceKm?.format1() ?: "?"
        val eurPerKm = offer.eurPerKm()?.format2() ?: "?"
        val eurPerHour = offer.eurPerHour()?.format2() ?: "?"
        val pickupPrefix = offer.pickupPostalCode?.take(4) ?: "----"
        val destinationPrefix = offer.destinationPostalCode?.take(4) ?: "----"
        return "${offer.sourceApp.name} | fare=$fare EUR | pickup=$pickupKm km | trip=$tripKm km | $eurPerKm EUR/km | $eurPerHour EUR/h | zones=$pickupPrefix->$destinationPrefix | decision=${result.decision}"
    }

    private fun pt.tripguard.app.core.storage.CaptureDebugEntry.toDiagnosticLine(): String {
        val time = localTimestamp(timestampMs)
        val pkg = packageName ?: "?"
        val rawExcerpt = excerpt?.takeIf { it.isNotBlank() } ?: "-"
        return "$time | stage=$stage | pkg=$pkg | source=$sourceHint | $detail | excerpt=$rawExcerpt"
    }

    private fun utcTimestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    private fun localTimestamp(timestampMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return formatter.format(Date(timestampMs))
    }

    private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)

    private fun Double.format2(): String = String.format(Locale.US, "%.2f", this)
}
