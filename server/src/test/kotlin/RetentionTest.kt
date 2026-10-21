package familytracker

import familytracker.db.Database
import familytracker.db.PointIn
import familytracker.db.insertLocations
import familytracker.db.locationHistory
import familytracker.db.createFamilyWithParent
import familytracker.db.redeemChildCode
import familytracker.db.storePairCode
import familytracker.db.RedeemResult
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetentionTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: Database
    private lateinit var deviceId: UUID

    private val day = 24L * 60 * 60 * 1000

    @BeforeTest
    fun setUp() {
        db = Database(tempDir.resolve("retention.db").toString())
        val parent = db.withConnection { createFamilyWithParent(it, "Mom") }
        val code = db.withConnection { storePairCode(it, parent.familyId) }
        val redeemed = db.withConnection { redeemChildCode(it, code.code, "Kid") }
        deviceId = (redeemed as RedeemResult.Redeemed).registration.deviceId
    }

    private fun insertPoint(recordedAt: Long) {
        db.withConnection {
            insertLocations(it, deviceId, listOf(PointIn(1.0, 2.0, null, null, recordedAt)))
        }
    }

    private fun recordedAts(): List<Long> =
        db.withConnection { locationHistory(it, deviceId, Long.MIN_VALUE, Long.MAX_VALUE) }
            .map { it.recordedAt }

    @Test
    fun `retention removes only points older than thirty days`() {
        val now = 1_800_000_000_000L
        insertPoint(now - 29 * day)
        insertPoint(now - 30 * day)
        insertPoint(now - 31 * day)

        val deleted = runRetentionOnce(db, now)

        assertEquals(1, deleted)
        assertEquals(listOf(now - 30 * day, now - 29 * day), recordedAts())
    }

    @Test
    fun `rerunning retention deletes nothing and does not throw`() {
        val now = 1_800_000_000_000L
        insertPoint(now - 31 * day)

        assertEquals(1, runRetentionOnce(db, now))
        assertEquals(0, runRetentionOnce(db, now))
        assertTrue(recordedAts().isEmpty())
    }

    @Test
    fun `scheduled retention repeats the run and removes points inserted later`() {
        val now = 1_800_000_000_000L
        val executor = scheduleRetention(db, periodMs = 50, now = { now })
        try {
            // Inserted after scheduleRetention returned: only a later tick can remove it.
            insertPoint(now - 31 * day)

            val deadline = System.currentTimeMillis() + 5_000
            while (recordedAts().isNotEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(recordedAts().isEmpty(), "expired point was not removed within 5s")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a failing run does not stop the schedule`() {
        val calls = AtomicInteger()
        val executor = scheduleRetention(
            db,
            periodMs = 50,
            run = {
                if (calls.incrementAndGet() <= 2) throw RuntimeException("transient failure")
                0
            },
        )
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (calls.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(calls.get() >= 3, "scheduler stopped after a failing run: ${calls.get()} calls")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `non-positive period is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            scheduleRetention(db, periodMs = 0)
        }
    }

    @Test
    fun `extreme now does not delete anything`() {
        insertPoint(1_000L)

        assertEquals(0, runRetentionOnce(db, Long.MIN_VALUE))
        assertEquals(listOf(1_000L), recordedAts())
    }

    @Test
    fun `retention window is thirty days`() {
        assertEquals(30L * 24 * 60 * 60 * 1000, LOCATION_RETENTION_MS)
    }
}
