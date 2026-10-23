package ru.family.tracker.parent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.family.tracker.AppContainer
import ru.family.tracker.FamilyTrackerApp
import ru.family.tracker.MainActivity
import ru.family.tracker.R
import ru.family.tracker.Role

/**
 * Keeps the parent's ntfy subscription alive while the app is not open.
 *
 * The service is intentionally quiet: its own channel is low-importance ("Family Tracker
 * активен"), while every SOS goes through [SosNotifier]'s high-importance channel. It
 * only runs for a paired parent with an ntfy configuration; otherwise it stops itself.
 */
class SosListenerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nameCache = HashMap<String, String>()

    private lateinit var container: AppContainer
    private var listener: SosListener? = null

    @Volatile
    private var started = false

    override fun onCreate() {
        super.onCreate()
        container = (application as FamilyTrackerApp).container
        val settings = container.settings
        val config = settings.ntfyConfig

        if (settings.role != Role.PARENT || settings.token.isNullOrBlank() || config == null) {
            stopSelf()
            return
        }

        createNotificationChannel()
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (error: Exception) {
            // Android 14+ can refuse a special-use service (or notifications are blocked
            // by policy); the UI can still start it again later.
            Log.w(TAG, "could not enter the foreground", error)
            stopSelf()
            return
        }
        started = true

        // One `/devices` request per service start; notification delivery must not wait
        // for (or repeat) a network call on every SOS.
        scope.launch { loadDeviceNames() }

        val sosListener = SosListener(
            api = container.api,
            socketFactory = { container.ntfySocketFactory(config) },
            sink = { event ->
                // Called synchronously on the listener coroutine: the marker may only
                // advance after the notification was actually posted.
                val delivered = SosNotifier(this@SosListenerService)
                    .notify(event, cachedChildName(event.child))
                if (delivered) {
                    SosAlertBus.show(
                        SosAlert(
                            eventId = event.eventId,
                            childId = event.child,
                            lat = event.lat,
                            lon = event.lon,
                            createdAtIso = event.createdAt,
                        ),
                    )
                }
                delivered
            },
            settings = settings,
            scope = scope,
            notificationsEnabled = {
                NotificationManagerCompat.from(this).areNotificationsEnabled()
            },
        )
        listener = sosListener
        sosListener.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (started) START_STICKY else START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        listener?.stop()
        listener = null
        scope.cancel()
        started = false
        super.onDestroy()
    }

    /**
     * Fills the service-lifetime name cache once at start. A failed `/devices` request is
     * not fatal: the notification falls back to the generic "ребёнок" title.
     */
    private suspend fun loadDeviceNames() {
        val devices = try {
            container.api.devices()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // No token/coordinates in the log line.
            Log.w(TAG, "could not resolve child names: ${error.javaClass.simpleName}")
            return
        }

        synchronized(nameCache) {
            devices.forEach { device -> nameCache[device.deviceId] = device.name }
        }
    }

    /** Cache-only lookup: notification delivery never performs network I/O. */
    private fun cachedChildName(childId: String): String? =
        synchronized(nameCache) { nameCache[childId] }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sos_listener_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.sos_listener_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle(getString(R.string.sos_listener_notification_title))
            .setContentText(getString(R.string.sos_listener_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "sos_listener"
        private const val NOTIFICATION_ID = 1002
        private const val TAG = "SosListenerService"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SosListenerService::class.java),
                )
            } catch (error: Exception) {
                // Background-start restrictions on some ROMs must not crash the caller;
                // the next launch/boot tries again.
                Log.w(TAG, "could not start sos listener", error)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SosListenerService::class.java))
        }
    }
}
