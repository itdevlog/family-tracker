package ru.family.tracker.parent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.family.tracker.MainActivity
import ru.family.tracker.R
import ru.family.tracker.data.api.SosEvent
import java.time.DateTimeException
import java.time.OffsetDateTime

/**
 * Builds and posts the parent's SOS notification.
 *
 * It always posts a high-importance alarm-category notification (the heads-up fallback)
 * and additionally attaches a full-screen intent. Android 14+ may deny full-screen
 * intents to non-alarm apps unless the user grants it, so the fallback is not optional:
 * an ordinary heads-up notification still appears if the activity is not raised.
 */
class SosNotifier(private val context: Context) {

    /**
     * Posts the alert. Returns false when the user blocked notifications (globally or for
     * this channel), or when the platform refuses the post — the caller must only advance
     * its delivery marker for a true result.
     */
    fun notify(event: SosEvent, childName: String?): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "notifications are disabled")
            return false
        }

        try {
            createChannel()
        } catch (error: Exception) {
            Log.w(TAG, "could not create the sos channel: ${error.javaClass.simpleName}")
            return false
        }

        val channel = try {
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
        } catch (_: Exception) {
            null
        }
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
            Log.w(TAG, "sos notification channel is blocked")
            return false
        }

        val name = childName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.sos_notification_child_fallback)
        val contentIntent = contentIntent(event)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle(context.getString(R.string.sos_notification_title, name))
            .setContentText(context.getString(R.string.sos_notification_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setWhen(createdAtMillis(event.createdAt))
            .setExtras(extras(event))
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .build()

        return try {
            manager.notify(event.eventId.hashCode(), notification)
            true
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS was revoked after the service started. The listener keeps
            // running (the user may re-enable notifications); there is nothing to alert.
            Log.w(TAG, "notification permission missing", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "notification post failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun contentIntent(event: SosEvent): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtras(extras(event))
        }
        return PendingIntent.getActivity(
            context,
            event.eventId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * The same payload on the notification (for tests/inspection) and on the launch
     * intent that [ru.family.tracker.MainActivity] consumes.
     */
    private fun extras(event: SosEvent): Bundle = Bundle().apply {
        putString(SosAlert.EXTRA_SOS_ID, event.eventId)
        putString(SosAlert.EXTRA_CHILD_ID, event.child)
        putDouble(SosAlert.EXTRA_LAT, event.lat)
        putDouble(SosAlert.EXTRA_LON, event.lon)
        putString(SosAlert.EXTRA_CREATED_AT, event.createdAt)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sos_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.sos_channel_description)
            // Default sound + vibration: an SOS must be noticeable, but the phone's
            // silent/DND settings stay in control of the actual outcome.
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun createdAtMillis(iso: String): Long = try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: DateTimeException) {
        0L
    } catch (_: ArithmeticException) {
        0L
    }

    companion object {
        const val CHANNEL_ID = "sos_alerts"
        private const val TAG = "SosNotifier"
    }
}
