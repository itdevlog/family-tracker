package ru.family.tracker.ui.child

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Relative upload time shown on the child home, with injectable clock and zone. */
class ChildUploadStatusTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val now = Instant.parse("2026-10-23T12:00:00Z").toEpochMilli()

    private fun ago(millis: Long): String =
        formatRelativeTime(thenMillis = now - millis, nowMillis = now, zone = zone)

    @Test
    fun `59 seconds is just now`() {
        assertEquals("только что", ago(59_000))
    }

    @Test
    fun `60 seconds is one minute`() {
        assertEquals("1 мин назад", ago(60_000))
    }

    @Test
    fun `59 minutes 59 seconds is still 59 minutes`() {
        assertEquals("59 мин назад", ago(59 * 60_000L + 59_000L))
    }

    @Test
    fun `one hour is one hour`() {
        assertEquals("1 ч назад", ago(3_600_000))
    }

    @Test
    fun `23 hours 59 minutes is still 23 hours`() {
        assertEquals("23 ч назад", ago(23 * 3_600_000L + 59 * 60_000L))
    }

    @Test
    fun `24 hours falls back to the date and time`() {
        assertEquals("22.10.2026 15:00", ago(24 * 3_600_000L))
    }

    @Test
    fun `future timestamps are clamped to just now`() {
        assertEquals("только что", formatRelativeTime(now + 5_000, now, zone))
    }

    @Test
    fun `a rejected token overrides online and the timestamp`() {
        val snapshot = UploadStatusSnapshot(
            lastUploadAtMillis = now - 60_000,
            errorCode = 401,
            online = true,
            nowMillis = now,
        )

        assertEquals(UploadStatusKind.REJECTED, uploadStatusKind(snapshot))
    }

    @Test
    fun `no timestamp is reported as never uploaded`() {
        val snapshot = UploadStatusSnapshot(
            lastUploadAtMillis = null,
            errorCode = null,
            online = true,
            nowMillis = now,
        )

        assertEquals(UploadStatusKind.NEVER, uploadStatusKind(snapshot))
    }

    @Test
    fun `no timestamp while offline is offline never`() {
        val snapshot = UploadStatusSnapshot(
            lastUploadAtMillis = null,
            errorCode = null,
            online = false,
            nowMillis = now,
        )

        assertEquals(UploadStatusKind.OFFLINE_NEVER, uploadStatusKind(snapshot))
    }

    @Test
    fun `a rejected token overrides offline never`() {
        val snapshot = UploadStatusSnapshot(
            lastUploadAtMillis = null,
            errorCode = 403,
            online = false,
            nowMillis = now,
        )

        assertEquals(UploadStatusKind.REJECTED, uploadStatusKind(snapshot))
    }

    @Test
    fun `timestamp is online or offline according to connectivity`() {
        val base = UploadStatusSnapshot(
            lastUploadAtMillis = now - 60_000,
            errorCode = null,
            online = true,
            nowMillis = now,
        )

        assertEquals(UploadStatusKind.ONLINE, uploadStatusKind(base))
        assertEquals(UploadStatusKind.OFFLINE, uploadStatusKind(base.copy(online = false)))
    }
}
