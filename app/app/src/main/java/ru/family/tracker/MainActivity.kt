package ru.family.tracker

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ru.family.tracker.parent.SosAlert
import ru.family.tracker.parent.SosAlertBus
import ru.family.tracker.ui.theme.FamilyTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOnLockScreen()
        showSosAlert(intent)

        val container = (application as FamilyTrackerApp).container
        setContent {
            FamilyTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FamilyTrackerRoot(container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showSosAlert(intent)
    }

    /**
     * A full-screen intent can arrive while the phone is locked. API 27+ has dedicated
     * activity APIs; API 26 (the app minimum) still supports the equivalent window flags.
     */
    private fun showOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
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
