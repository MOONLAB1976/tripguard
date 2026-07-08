package pt.tripguard.app.core.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TripAdviceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            TripAdviceScheduler.ensureScheduled(context)
        }
    }
}
