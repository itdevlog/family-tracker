package ru.family.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server has no "no location" value: a child without a fix sends `0.0/0.0`.
 * These tests pin the client-side interpretation used by every parent surface.
 */
class LocationKnowledgeTest {

    @Test
    fun `exact zero is unknown`() {
        assertFalse(hasKnownLocation(0.0, 0.0))
        assertNull(mapPointOrNull(0.0, 0.0))
    }

    @Test
    fun `near-zero values are treated as the same unknown sentinel`() {
        assertFalse(hasKnownLocation(0.00000001, -0.00000001))
        assertFalse(hasKnownLocation(-0.00000001, 0.00000001))
    }

    @Test
    fun `a real fix is known even at one non-zero coordinate`() {
        assertTrue(hasKnownLocation(55.7558, 37.6173))
        assertTrue(hasKnownLocation(0.0, 37.6173))
        assertTrue(hasKnownLocation(-0.1, 0.0))

        val point = mapPointOrNull(55.7558, 37.6173)
        assertNotNull(point)
        assertEquals(55.7558, point!!.lat, 0.0)
        assertEquals(37.6173, point.lon, 0.0)
    }
}
