package ru.family.tracker.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the movement classification and update intervals. */
class TrackingPolicyTest {

    @Test
    fun `null speed is not moving`() {
        assertFalse(isMoving(null))
    }

    @Test
    fun `slow speed is not moving`() {
        assertFalse(isMoving(0.5f))
    }

    @Test
    fun `speed at the threshold is not moving`() {
        assertFalse(isMoving(1.0f))
    }

    @Test
    fun `speed just above the threshold is moving`() {
        assertTrue(isMoving(1.01f))
    }

    @Test
    fun `negative speed is not moving`() {
        assertFalse(isMoving(-3f))
    }

    @Test
    fun `moving interval is ten seconds`() {
        assertEquals(10_000L, intervalFor(moving = true))
    }

    @Test
    fun `idle interval is one minute`() {
        assertEquals(60_000L, intervalFor(moving = false))
    }
}
