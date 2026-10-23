package ru.family.tracker.parent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.family.tracker.FamilyTrackerApp
import ru.family.tracker.Role

/** Restarts the parent's SOS subscription after a reboot. */
class SosBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != ACTION_QUICKBOOT) {
            return
        }

        val container = (context.applicationContext as? FamilyTrackerApp)?.container ?: return
        val settings = container.settings
        if (settings.role != Role.PARENT) return
        if (settings.token.isNullOrBlank()) return
        if (settings.ntfyConfig == null) return

        SosListenerService.start(context)
    }

    companion object {
        /** Some vendor ROMs send this instead of (or before) BOOT_COMPLETED. */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
