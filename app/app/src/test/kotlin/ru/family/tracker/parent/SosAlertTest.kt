package ru.family.tracker.parent

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Intent parsing and the tiny alert bus that connects the notification to Compose. */
@RunWith(RobolectricTestRunner::class)
class SosAlertTest {

    private fun validIntent() = Intent().apply {
        putExtra(SosAlert.EXTRA_SOS_ID, "evt-1")
        putExtra(SosAlert.EXTRA_CHILD_ID, "child-1")
        putExtra(SosAlert.EXTRA_LAT, 55.71)
        putExtra(SosAlert.EXTRA_LON, 37.61)
        putExtra(SosAlert.EXTRA_CREATED_AT, "2026-10-20T10:10:00Z")
    }

    @Test
    fun `parses a complete sos intent`() {
        val alert = SosAlert.fromIntent(validIntent())

        assertEquals(
            SosAlert(
                eventId = "evt-1",
                childId = "child-1",
                lat = 55.71,
                lon = 37.61,
                createdAtIso = "2026-10-20T10:10:00Z",
            ),
            alert,
        )
    }

    @Test
    fun `ordinary launch intent is ignored`() {
        assertNull(SosAlert.fromIntent(Intent(Intent.ACTION_MAIN)))
        assertNull(SosAlert.fromIntent(null))
    }

    @Test
    fun `missing or invalid extras are ignored`() {
        val noChild = validIntent().apply { removeExtra(SosAlert.EXTRA_CHILD_ID) }
        val blankId = validIntent().apply { putExtra(SosAlert.EXTRA_SOS_ID, " ") }
        val badLat = validIntent().apply { putExtra(SosAlert.EXTRA_LAT, Double.NaN) }
        val outOfRange = validIntent().apply { putExtra(SosAlert.EXTRA_LON, 181.0) }
        val wrongType = validIntent().apply { putExtra(SosAlert.EXTRA_LAT, "55.71") }

        assertNull(SosAlert.fromIntent(noChild))
        assertNull(SosAlert.fromIntent(blankId))
        assertNull(SosAlert.fromIntent(badLat))
        assertNull(SosAlert.fromIntent(outOfRange))
        assertNull(SosAlert.fromIntent(wrongType))
    }

    @Test
    fun `bus shows and clears the current alert`() {
        val alert = SosAlert.fromIntent(validIntent())!!
        SosAlertBus.show(alert)
        assertEquals(alert, SosAlertBus.alert.value)

        SosAlertBus.clear()
        assertNull(SosAlertBus.alert.value)
    }

    @Test
    fun `showing the same event again keeps the current instance`() {
        val alert = SosAlert.fromIntent(validIntent())!!
        SosAlertBus.show(alert)

        val duplicate = alert.copy(createdAtIso = "2026-10-20T10:11:00Z")
        SosAlertBus.show(duplicate)

        assertEquals(alert, SosAlertBus.alert.value)
        SosAlertBus.clear()
    }

    @Test
    fun `zero coordinates produce no alert map point while a real fix does`() {
        val alert = SosAlert.fromIntent(validIntent())!!
        assertNotNull(alert.mapPoint())

        assertNull(alert.copy(lat = 0.0, lon = 0.0).mapPoint())
    }
}
