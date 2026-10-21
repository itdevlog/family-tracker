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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `scheduled retention runs immediately and removes expired points`() {
        val expired = System.currentTimeMillis() - 31 * day
        insertPoint(expired)

        val executor = scheduleRetention(db, periodMs = 3_600_000)
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (recordedAts().isNotEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
            assertTrue(recordedAts().isEmpty(), "expired point was not removed within 5s")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `retention window is thirty days`() {
        assertEquals(2_592_000_000L, LOCATION_RETENTION_MS)
        assertEquals(30L * 24 * 60 * 60 * 1000, LOCATION_RETENTION_MS)
    }
}
