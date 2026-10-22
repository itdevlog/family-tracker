package ru.family.tracker.ui.parent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class PairingExpiryTest {

    @Test
    fun `expiry is rendered in the given zone as hours and minutes`() {
        val instant = Instant.parse("2026-10-20T11:05:00Z")

        assertEquals("11:05", formatLocalExpiryTime(instant, ZoneOffset.UTC))
        assertEquals("14:05", formatLocalExpiryTime(instant, ZoneOffset.ofHours(3)))
    }

    @Test
    fun `countdown is mm ss and never negative`() {
        assertEquals("02:05", formatRemainingTime(125_000))
        assertEquals("00:00", formatRemainingTime(0))
        assertEquals("00:00", formatRemainingTime(-5_000))
    }
}
