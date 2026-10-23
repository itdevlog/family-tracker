package ru.family.tracker

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.family.tracker.parent.SosAlert
import ru.family.tracker.parent.SosAlertBus
import ru.family.tracker.ui.theme.FamilyTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 enforces edge-to-edge; every root applies the safe-drawing insets.
        enableEdgeToEdge()
        showSosAlert(intent)
        setSosLockScreen(SosAlertBus.alert.value != null)

        val container = (application as FamilyTrackerApp).container
        setContent {
            FamilyTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FamilyTrackerRoot(container)
                }
            }
        }

        // The overlay is the single owner of "an alert is visible": ack, "Позже" and
        // back all clear the bus, and that must also release the lock-screen flags.
        lifecycleScope.launch {
            SosAlertBus.alert.collect { alert -> setSosLockScreen(alert != null) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showSosAlert(intent)
        setSosLockScreen(SosAlertBus.alert.value != null)
    }

    /**
     * Shows this activity over the keyguard only while a full-screen SOS alert is open.
     *
     * A normal map/history session must not become visible on the lock screen: the parent
     * would otherwise see the child's coordinates without unlocking the phone. Both the
     * modern activity APIs (API 27+) and the equivalent window flags are kept in sync so
     * `enabled = false` reliably removes either form.
     */
    internal fun setSosLockScreen(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(enabled)
            setTurnScreenOn(enabled)
        }
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        if (enabled) {
            @Suppress("DEPRECATION")
            window.addFlags(flags)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(flags)
        }
    }

    /**
     * A full-screen intent or a notification tap can (re)launch the activity with SOS
     * extras. The bus is process-wide because the root composable may outlive the
     * activity instance that received the intent.
     */
    private fun showSosAlert(intent: Intent?) {
        val alert = SosAlert.fromIntent(intent) ?: return
        SosAlertBus.show(alert)
        // Consumed extras must not resurface on the next configuration change.
        intent?.removeExtra(SosAlert.EXTRA_SOS_ID)
    }
}
