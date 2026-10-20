package familytracker.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    private var now = 0L

    private fun limiter(
        maxFailures: Int = 5,
        windowMs: Long = 60_000,
        maxKeys: Int = 10_000,
    ) = RateLimiter(
        maxFailures = maxFailures,
        windowMs = windowMs,
        maxKeys = maxKeys,
        clock = { now },
    )

    @Test
    fun `tryAcquire allows the limit and rejects the next attempt`() {
        val limiter = limiter(maxFailures = 2)

        assertTrue(limiter.tryAcquire("1.2.3.4"))
        assertTrue(limiter.tryAcquire("1.2.3.4"))
        assertFalse(limiter.tryAcquire("1.2.3.4"))
        assertTrue(limiter.tryAcquire("5.6.7.8"))
    }

    @Test
    fun `refund gives a successful attempt back to the budget`() {
        val limiter = limiter(maxFailures = 2)

        assertTrue(limiter.tryAcquire("1.2.3.4"))
        assertTrue(limiter.tryAcquire("1.2.3.4"))
        limiter.refund("1.2.3.4")

        assertTrue(limiter.tryAcquire("1.2.3.4"))
        assertFalse(limiter.tryAcquire("1.2.3.4"))
    }

    @Test
    fun `window slides with injected clock`() {
        val limiter = limiter(maxFailures = 2, windowMs = 1_000)

        now = 0
        assertTrue(limiter.tryAcquire("1.2.3.4"))
        now = 500
        assertTrue(limiter.tryAcquire("1.2.3.4"))
        now = 999
        assertFalse(limiter.tryAcquire("1.2.3.4"), "both attempts are still inside the window")

        // At t=1000 the t=0 attempt falls out of the window but the t=500 one remains.
        now = 1_000
        assertTrue(limiter.tryAcquire("1.2.3.4"))
        assertFalse(limiter.tryAcquire("1.2.3.4"))
    }

    @Test
    fun `oldest keys are evicted when the key cap is reached`() {
        val limiter = limiter(maxKeys = 3)

        listOf("k1", "k2", "k3", "k4", "k5").forEach { key ->
            now += 1
            assertTrue(limiter.tryAcquire(key))
        }

        assertEquals(3, limiter.trackedKeys().size)
        assertEquals(setOf("k3", "k4", "k5"), limiter.trackedKeys())
    }

    @Test
    fun `expired keys are pruned before live keys are evicted`() {
        val limiter = limiter(maxKeys = 3, windowMs = 1_000)

        now = 0
        assertTrue(limiter.tryAcquire("old1"))
        now = 100
        assertTrue(limiter.tryAcquire("old2"))
        now = 200
        assertTrue(limiter.tryAcquire("old3"))

        now = 2_000
        assertTrue(limiter.tryAcquire("fresh"))

        assertEquals(setOf("fresh"), limiter.trackedKeys())
    }
}
