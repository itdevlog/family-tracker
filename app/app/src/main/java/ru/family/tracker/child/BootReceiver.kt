package ru.family.tracker.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import ru.family.tracker.FamilyTrackerApp

/** Restarts tracking after a reboot, but only for a paired child. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != ACTION_QUICKBOOT) {
            return
        }
        val container = (context.applicationContext as? FamilyTrackerApp)?.container ?: return
        if (!container.settings.canTrack()) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java),
            )
        } catch (error: Exception) {
            // A background-start restriction must not crash the boot receiver; the user
            // can still start tracking from the UI.
            Log.w(TAG, "could not restart tracking after boot", error)
        }
    }

    companion object {
        /** Some vendor ROMs send this instead of (or before) BOOT_COMPLETED. */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"

        private const val TAG = "BootReceiver"
    }
}
