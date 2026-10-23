package ru.family.tracker.parent

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import ru.family.tracker.data.api.SosEvent

/** The SOS notification must be a high-importance alarm that also carries a full-screen intent. */
@RunWith(RobolectricTestRunner::class)
class SosNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    private val event = SosEvent(
        eventId = "evt-1",
        child = "child-1",
        lat = 55.71,
        lon = 37.61,
        createdAt = "2026-10-20T10:10:00Z",
        acknowledged = false,
    )

    @Test
    fun `posts high importance alarm with sos extras and a full screen intent`() {
        assertTrue(SosNotifier(context).notify(event, "Маша"))

        val channel = manager.getNotificationChannel(SosNotifier.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.shouldVibrate())
        assertNotNull(channel.sound)

        val notification = shadowOf(manager).getNotification(event.eventId.hashCode())
        assertNotNull(notification)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(Notification.PRIORITY_MAX, notification.priority)
        assertNotNull(notification.fullScreenIntent)
        assertNotNull(notification.contentIntent)

        assertEquals("evt-1", notification.extras.getString(SosAlert.EXTRA_SOS_ID))
        assertEquals("child-1", notification.extras.getString(SosAlert.EXTRA_CHILD_ID))
        assertEquals(55.71, notification.extras.getDouble(SosAlert.EXTRA_LAT), 0.0)
        assertEquals(37.61, notification.extras.getDouble(SosAlert.EXTRA_LON), 0.0)
        assertEquals(
            "2026-10-20T10:10:00Z",
            notification.extras.getString(SosAlert.EXTRA_CREATED_AT),
        )

        val alert = SosAlert.fromIntent(shadowOf(notification.contentIntent).savedIntent)
        assertEquals(event.eventId, alert?.eventId)
        assertEquals(event.child, alert?.childId)

        // A second alert while MainActivity is already open must arrive via onNewIntent,
        // not recreate the activity on top of the stack.
        val launchFlags = shadowOf(notification.contentIntent).savedIntent.flags
        assertTrue(launchFlags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(launchFlags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun `notifications disabled means not delivered and nothing is posted`() {
        shadowOf(manager).setNotificationsEnabled(false)

        assertFalse(SosNotifier(context).notify(event, "Маша"))
        assertNull(shadowOf(manager).getNotification(event.eventId.hashCode()))
    }

    @Test
    fun `unknown child name uses the fallback title`() {
        assertTrue(SosNotifier(context).notify(event, null))

        val notification = shadowOf(manager).getNotification(event.eventId.hashCode())
        assertEquals(
            context.getString(ru.family.tracker.R.string.sos_notification_title, "ребёнка"),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
    }
}
