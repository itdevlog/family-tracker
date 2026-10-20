package familytracker

import familytracker.db.AuthDevice
import familytracker.db.ChildRegistration
import familytracker.db.Database
import familytracker.db.DeviceSummary
import familytracker.db.FamilyAlreadyExistsException
import familytracker.db.PairCode
import familytracker.db.PointIn
import familytracker.db.RedeemResult
import familytracker.db.SosEvent
import familytracker.db.ackSos
import familytracker.db.childrenWithLastLocation
import familytracker.db.createFamilyWithParent
import familytracker.db.deleteOldLocations
import familytracker.db.familyExists
import familytracker.db.findDeviceByToken
import familytracker.db.insertLocations
import familytracker.db.insertSos
import familytracker.db.listSos
import familytracker.db.locationHistory
import familytracker.db.newToken
import familytracker.db.redeemChildCode
import familytracker.db.sha256Hex
import familytracker.db.storePairCode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaoTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: Database

    @BeforeTest
    fun setUp() {
        db = Database(tempDir.resolve("test.db").toString())
    }

    @Test
    fun `fresh database has no family`() {
        assertFalse(db.withConnection { familyExists(it) })
    }

    @Test
    fun `createFamilyWithParent creates family parent device and ntfy topic`() {
        val parent = db.withConnection { conn ->
            val registration = createFamilyWithParent(conn, "Mom")
            assertTrue(familyExists(conn))
            registration
        }

        assertEquals("family-${parent.familyId}-sos", parent.ntfyTopic)
        assertTrue(parent.token.isNotBlank())

        val device = db.withConnection { findDeviceByToken(it, parent.token) }
        assertNotNull(device)
        assertEquals(parent.deviceId, device.id)
        assertEquals(parent.familyId, device.familyId)
        assertEquals("parent", device.role)
        assertEquals("Mom", device.name)
    }

    @Test
    fun `createFamilyWithParent rejects second family`() {
        db.withConnection { createFamilyWithParent(it, "Mom") }

        assertFailsWith<FamilyAlreadyExistsException> {
            db.withConnection { createFamilyWithParent(it, "Other parent") }
        }
    }

    @Test
    fun `tokens differ between parent and child registrations`() {
        val parent = createFamily()
        val child = addChild(parent, "Kid")

        assertNotEquals(parent.token, child.token)
        assertNotEquals(sha256Hex(parent.token), sha256Hex(child.token))

        db.withConnection { conn ->
            val parentDevice = assertNotNull(findDeviceByToken(conn, parent.token))
            val childDevice = assertNotNull(findDeviceByToken(conn, child.token))
            assertEquals("parent", parentDevice.role)
            assertEquals("child", childDevice.role)
            assertEquals(child.deviceId, childDevice.id)
            assertEquals(parent.familyId, child.familyId)
            assertNull(findDeviceByToken(conn, "definitely-not-a-token"))
        }
    }

    @Test
    fun `token generation and sha256 hash are stable`() {
        val first = newToken()
        val second = newToken()

        assertNotEquals(first, second)
        assertEquals(43, first.length)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
        assertEquals(sha256Hex(first), sha256Hex(first))
        assertNotEquals(sha256Hex(first), sha256Hex(second))
    }

    @Test
    fun `storePairCode returns six digits valid for five minutes`() {
        val parent = createFamily()
        val before = System.currentTimeMillis()

        val code = db.withConnection { storePairCode(it, parent.familyId) }

        assertTrue(code.code.matches(Regex("\\d{6}")), "unexpected code ${code.code}")
        assertTrue(
            code.expiresAt in (before + 4 * 60_000L)..(before + 6 * 60_000L),
            "unexpected expiry ${code.expiresAt}",
        )
    }

    @Test
    fun `redeemChildCode redeems once then reports Used`() {
        val parent = createFamily()
        val code = db.withConnection { storePairCode(it, parent.familyId) }

        val first = db.withConnection { redeemChildCode(it, code.code, "Kid") }
        val registration = assertIs<RedeemResult.Redeemed>(first).registration
        assertEquals(parent.familyId, registration.familyId)
        assertTrue(registration.token.isNotBlank())

        val second = db.withConnection { redeemChildCode(it, code.code, "Kid again") }
        assertEquals(RedeemResult.Used, second)

        val device = db.withConnection { findDeviceByToken(it, registration.token) }
        assertEquals(registration.deviceId, device?.id)
        assertEquals("child", device?.role)
    }

    @Test
    fun `redeemChildCode reports Invalid for unknown code`() {
        createFamily()

        val result = db.withConnection { redeemChildCode(it, "000000", "Kid") }

        assertEquals(RedeemResult.Invalid, result)
    }

    @Test
    fun `redeemChildCode reports Expired for stale code`() {
        val parent = createFamily()
        val code = db.withConnection { storePairCode(it, parent.familyId) }
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE pair_codes SET expires_at = ? WHERE code = ?").use { statement ->
                statement.setLong(1, System.currentTimeMillis() - 1)
                statement.setString(2, code.code)
                statement.executeUpdate()
            }
        }

        val result = db.withConnection { redeemChildCode(it, code.code, "Kid") }

        assertEquals(RedeemResult.Expired, result)
    }

    @Test
    fun `insertLocations deduplicates repeated batch`() {
        val child = addChild(createFamily(), "Kid")
        val points = listOf(
            PointIn(55.0, 37.0, 5f, 1.5f, 1_000L),
            PointIn(55.1, 37.1, null, null, 2_000L),
            PointIn(55.2, 37.2, 10f, 0f, 3_000L),
        )

        val inserted = db.withConnection { insertLocations(it, child.deviceId, points) }
        val repeated = db.withConnection { insertLocations(it, child.deviceId, points) }

        assertEquals(3, inserted)
        assertEquals(0, repeated)
        assertEquals(3, db.withConnection { locationHistory(it, child.deviceId, 0L, Long.MAX_VALUE).size })
    }

    @Test
    fun `insertLocations surfaces foreign key violations instead of ignoring them`() {
        assertFailsWith<SQLException> {
            db.withConnection { conn ->
                insertLocations(conn, UUID.randomUUID(), listOf(PointIn(1.0, 1.0, null, null, 1_000L)))
            }
        }
    }

    @Test
    fun `locationHistory filters by inclusive time range`() {
        val child = addChild(createFamily(), "Kid")
        db.withConnection {
            insertLocations(
                it,
                child.deviceId,
                listOf(
                    PointIn(1.0, 1.0, 1f, 1f, 1_000L),
                    PointIn(2.0, 2.0, 2f, 2f, 2_000L),
                    PointIn(3.0, 3.0, 3f, 3f, 3_000L),
                ),
            )
        }

        val filtered = db.withConnection { locationHistory(it, child.deviceId, 1_500L, 2_500L) }
        val all = db.withConnection { locationHistory(it, child.deviceId, 1_000L, 3_000L) }
        val empty = db.withConnection { locationHistory(it, child.deviceId, 3_001L, Long.MAX_VALUE) }

        assertEquals(listOf(2_000L), filtered.map { it.recordedAt })
        assertEquals(2.0, filtered.single().lat)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), all.map { it.recordedAt })
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `childrenWithLastLocation returns newest point or null`() {
        val parent = createFamily()
        val alpha = addChild(parent, "Alpha")
        val beta = addChild(parent, "Beta")
        db.withConnection {
            insertLocations(
                it,
                alpha.deviceId,
                listOf(
                    PointIn(1.0, 1.0, 5f, 0.5f, 1_000L),
                    PointIn(2.0, 2.0, 6f, 0.6f, 2_000L),
                ),
            )
        }

        val children = db.withConnection { childrenWithLastLocation(it, parent.familyId) }

        assertEquals(listOf(alpha.deviceId, beta.deviceId), children.map { it.deviceId })
        val alphaSummary = children.first()
        assertEquals("Alpha", alphaSummary.name)
        assertNotNull(alphaSummary.lastLocation)
        assertEquals(2_000L, alphaSummary.lastLocation.recordedAt)
        assertEquals(2.0, alphaSummary.lastLocation.lat)
        assertEquals(6f, alphaSummary.lastLocation.accuracy)
        val betaSummary = children.single { it.deviceId == beta.deviceId }
        assertEquals("Beta", betaSummary.name)
        assertNull(betaSummary.lastLocation)
    }

    @Test
    fun `sos events can be listed acknowledged and filtered by time`() {
        val parent = createFamily()
        val child = addChild(parent, "Kid")
        val event = db.withConnection { insertSos(it, child.deviceId, 55.7, 37.6) }

        assertEquals(child.deviceId, event.childId)
        assertEquals(55.7, event.lat)
        assertEquals(37.6, event.lon)
        assertFalse(event.acknowledged)

        val listed = db.withConnection { listSos(it, parent.familyId, 0L) }
        assertEquals(listOf(event.id), listed.map { it.id })

        assertFalse(db.withConnection { ackSos(it, event.id, UUID.randomUUID()) })
        assertTrue(db.withConnection { ackSos(it, event.id, parent.familyId) })
        assertTrue(db.withConnection { listSos(it, parent.familyId, 0L) }.single().acknowledged)

        val afterEvent = db.withConnection { listSos(it, parent.familyId, event.createdAt + 1) }
        assertTrue(afterEvent.isEmpty())
    }

    @Test
    fun `deleteOldLocations removes only points before cutoff`() {
        val child = addChild(createFamily(), "Kid")
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1_000L
        db.withConnection {
            insertLocations(
                it,
                child.deviceId,
                listOf(
                    PointIn(1.0, 1.0, null, null, now - 31 * day),
                    PointIn(2.0, 2.0, null, null, now - 29 * day),
                ),
            )
        }

        val deleted = db.withConnection { deleteOldLocations(it, now - 30 * day) }
        val remaining = db.withConnection { locationHistory(it, child.deviceId, 0L, Long.MAX_VALUE) }

        assertEquals(1, deleted)
        assertEquals(1, remaining.size)
        assertEquals(2.0, remaining.single().lat)
    }

    private fun createFamily(): familytracker.db.ParentRegistration =
        db.withConnection { createFamilyWithParent(it, "Parent") }

    private fun addChild(parent: familytracker.db.ParentRegistration, name: String): ChildRegistration {
        val registration = db.withConnection { conn ->
            val code = storePairCode(conn, parent.familyId)
            val result = redeemChildCode(conn, code.code, name)
            assertIs<RedeemResult.Redeemed>(result).registration
        }
        assertEquals(parent.familyId, registration.familyId)
        return registration
    }
}
