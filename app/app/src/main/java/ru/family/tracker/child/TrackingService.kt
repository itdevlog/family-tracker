package ru.family.tracker.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.family.tracker.AppContainer
import ru.family.tracker.FamilyTrackerApp
import ru.family.tracker.MainActivity
import ru.family.tracker.R
import ru.family.tracker.data.api.PointIn
import ru.family.tracker.data.db.toEntity

/**
 * Foreground location service for a paired child.
 *
 * Every fix is appended to the Room queue and a network-constrained flush is requested;
 * the update cadence follows the movement policy (10 s while moving, 60 s while idle).
 * Coordinates and the token are never logged.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var container: AppContainer

    private var locationSource: LocationSource? = null

    @Volatile
    private var movingState: Boolean = true

    @Volatile
    private var trackingStarted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        container = (application as FamilyTrackerApp).container

        if (!container.settings.canTrack()) {
            stopSelf()
            return
        }

        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (error: Exception) {
            // Android 14+ refuses a location foreground service without the while-in-use
            // permission (e.g. a boot restart before the "Always" wizard is completed).
            Log.w(TAG, "could not enter the foreground", error)
            stopSelf()
            return
        }
        trackingStarted = true

        val source = container.locationSourceFactory(this)
        locationSource = source
        source.requestUpdates(
            intervalMs = intervalFor(moving = movingState),
            highAccuracy = movingState,
            onLocation = ::onLocation,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (trackingStarted) START_STICKY else START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locationSource?.stop()
        locationSource = null
        trackingStarted = false
        scope.cancel()
        super.onDestroy()
    }

    private fun onLocation(point: PointIn) {
        scope.launch {
            container.room.points().insert(point.toEntity())
            try {
                container.flushScheduler(this@TrackingService)
            } catch (error: Exception) {
                Log.w(TAG, "could not enqueue a location flush", error)
            }
        }

        val moving = isMoving(point.speed)
        if (moving != movingState) {
            movingState = moving
            locationSource?.requestUpdates(
                intervalMs = intervalFor(moving),
                highAccuracy = moving,
                onLocation = ::onLocation,
            )
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.tracking_channel_description)
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
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "TrackingService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java),
            )
        }

        fun stop(context: Context) {
            // Stopping tracking must also stop the queued backlog from being uploaded
            // every 15 minutes behind the user's back.
            LocationBufferWorker.cancel(context)
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
