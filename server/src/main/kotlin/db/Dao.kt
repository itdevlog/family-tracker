package familytracker.db

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.Base64
import java.util.Locale
import java.util.UUID

data class PointIn(
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val speed: Float?,
    val recordedAt: Long,
)

data class ParentRegistration(
    val deviceId: UUID,
    val familyId: UUID,
    val token: String,
    val ntfyTopic: String,
)

data class ChildRegistration(
    val deviceId: UUID,
    val familyId: UUID,
    val token: String,
)

data class AuthDevice(
    val id: UUID,
    val familyId: UUID,
    val role: String,
    val name: String,
)

data class DeviceSummary(
    val deviceId: UUID,
    val name: String,
    val lastLocation: PointIn?,
)

data class SosEvent(
    val id: UUID,
    val childId: UUID,
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
    val acknowledged: Boolean,
)

data class PairCode(
    val code: String,
    val expiresAt: Long,
)

sealed interface RedeemResult {
    data class Redeemed(val registration: ChildRegistration) : RedeemResult
    data object Used : RedeemResult
    data object Invalid : RedeemResult
    data object Expired : RedeemResult
}

private val secureRandom = SecureRandom()

private const val PAIR_CODE_TTL_MS = 5 * 60 * 1_000L
private const val PAIR_CODE_ATTEMPTS = 10

fun newToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun sha256Hex(raw: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

fun familyExists(conn: Connection): Boolean =
    conn.prepareStatement("SELECT 1 FROM families LIMIT 1").use { statement ->
        statement.executeQuery().use { it.next() }
    }

fun createFamilyWithParent(conn: Connection, name: String): ParentRegistration = inTransaction(conn) {
    val familyId = UUID.randomUUID()
    val deviceId = UUID.randomUUID()
    val token = newToken()
    val now = System.currentTimeMillis()

    val inserted = conn.prepareStatement(
        "INSERT INTO families(id, created_at) " +
            "SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM families)",
    ).use { statement ->
        statement.setString(1, familyId.toString())
        statement.setLong(2, now)
        statement.executeUpdate()
    }
    check(inserted == 1) { "a family already exists" }

    conn.prepareStatement(
        "INSERT INTO devices(id, family_id, role, name, token_hash, created_at) " +
            "VALUES (?, ?, 'parent', ?, ?, ?)",
    ).use { statement ->
        statement.setString(1, deviceId.toString())
        statement.setString(2, familyId.toString())
        statement.setString(3, name)
        statement.setString(4, sha256Hex(token))
        statement.setLong(5, now)
        statement.executeUpdate()
    }

    ParentRegistration(deviceId, familyId, token, "family-$familyId-sos")
}

fun findDeviceByToken(conn: Connection, rawToken: String): AuthDevice? =
    conn.prepareStatement(
        "SELECT id, family_id, role, name FROM devices WHERE token_hash = ?",
    ).use { statement ->
        statement.setString(1, sha256Hex(rawToken))
        statement.executeQuery().use { result ->
            if (!result.next()) {
                null
            } else {
                AuthDevice(
                    id = UUID.fromString(result.getString("id")),
                    familyId = UUID.fromString(result.getString("family_id")),
                    role = result.getString("role"),
                    name = result.getString("name"),
                )
            }
        }
    }

fun storePairCode(conn: Connection, familyId: UUID): PairCode {
    val expiresAt = System.currentTimeMillis() + PAIR_CODE_TTL_MS
    repeat(PAIR_CODE_ATTEMPTS) {
        val code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000))
        val exists = conn.prepareStatement("SELECT 1 FROM pair_codes WHERE code = ?").use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { it.next() }
        }
        if (!exists) {
            conn.prepareStatement(
                "INSERT INTO pair_codes(code, family_id, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, code)
                statement.setString(2, familyId.toString())
                statement.setLong(3, expiresAt)
                statement.executeUpdate()
            }
            return PairCode(code, expiresAt)
        }
    }
    error("could not allocate a unique pair code")
}

fun redeemChildCode(conn: Connection, code: String, name: String): RedeemResult = inTransaction(conn) {
    val row = conn.prepareStatement(
        "SELECT family_id, expires_at, used_by FROM pair_codes WHERE code = ?",
    ).use { statement ->
        statement.setString(1, code)
        statement.executeQuery().use { result ->
            if (!result.next()) {
                null
            } else {
                Triple(
                    result.getString("family_id"),
                    result.getLong("expires_at"),
                    result.getString("used_by"),
                )
            }
        }
    } ?: return@inTransaction RedeemResult.Invalid

    val (familyId, expiresAt, usedBy) = row
    if (usedBy != null) return@inTransaction RedeemResult.Used
    if (expiresAt <= System.currentTimeMillis()) return@inTransaction RedeemResult.Expired

    val deviceId = UUID.randomUUID()
    val claimed = conn.prepareStatement(
        "UPDATE pair_codes SET used_by = ? WHERE code = ? AND used_by IS NULL",
    ).use { statement ->
        statement.setString(1, deviceId.toString())
        statement.setString(2, code)
        statement.executeUpdate()
    }
    if (claimed == 0) return@inTransaction RedeemResult.Used

    val token = newToken()
    conn.prepareStatement(
        "INSERT INTO devices(id, family_id, role, name, token_hash, created_at) " +
            "VALUES (?, ?, 'child', ?, ?, ?)",
    ).use { statement ->
        statement.setString(1, deviceId.toString())
        statement.setString(2, familyId)
        statement.setString(3, name)
        statement.setString(4, sha256Hex(token))
        statement.setLong(5, System.currentTimeMillis())
        statement.executeUpdate()
    }

    RedeemResult.Redeemed(
        ChildRegistration(deviceId, UUID.fromString(familyId), token),
    )
}

fun insertLocations(conn: Connection, deviceId: UUID, points: List<PointIn>): Int {
    if (points.isEmpty()) return 0
    return conn.prepareStatement(
        "INSERT OR IGNORE INTO locations(device_id, lat, lon, accuracy, speed, recorded_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
    ).use { statement ->
        points.forEach { point ->
            statement.setString(1, deviceId.toString())
            statement.setDouble(2, point.lat)
            statement.setDouble(3, point.lon)
            if (point.accuracy == null) {
                statement.setNull(4, Types.REAL)
            } else {
                statement.setFloat(4, point.accuracy)
            }
            if (point.speed == null) {
                statement.setNull(5, Types.REAL)
            } else {
                statement.setFloat(5, point.speed)
            }
            statement.setLong(6, point.recordedAt)
            statement.addBatch()
        }
        statement.executeBatch().sum()
    }
}

fun childrenWithLastLocation(conn: Connection, familyId: UUID): List<DeviceSummary> =
    conn.prepareStatement(
        """
        SELECT d.id AS device_id, d.name AS name,
               l.lat AS lat, l.lon AS lon, l.accuracy AS accuracy,
               l.speed AS speed, l.recorded_at AS recorded_at
        FROM devices d
        LEFT JOIN locations l
            ON l.device_id = d.id
           AND l.recorded_at = (SELECT MAX(l2.recorded_at) FROM locations l2 WHERE l2.device_id = d.id)
        WHERE d.family_id = ? AND d.role = 'child'
        ORDER BY d.name ASC, d.id ASC
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, familyId.toString())
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    val recordedAt = result.getLong("recorded_at")
                    val hasLocation = !result.wasNull()
                    add(
                        DeviceSummary(
                            deviceId = UUID.fromString(result.getString("device_id")),
                            name = result.getString("name"),
                            lastLocation = if (hasLocation) result.readPoint() else null,
                        ),
                    )
                }
            }
        }
    }

fun locationHistory(conn: Connection, deviceId: UUID, from: Long, to: Long): List<PointIn> =
    conn.prepareStatement(
        "SELECT lat, lon, accuracy, speed, recorded_at FROM locations " +
            "WHERE device_id = ? AND recorded_at >= ? AND recorded_at <= ? " +
            "ORDER BY recorded_at ASC",
    ).use { statement ->
        statement.setString(1, deviceId.toString())
        statement.setLong(2, from)
        statement.setLong(3, to)
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    add(result.readPoint())
                }
            }
        }
    }

fun insertSos(conn: Connection, childId: UUID, lat: Double, lon: Double): SosEvent {
    val id = UUID.randomUUID()
    val createdAt = System.currentTimeMillis()
    conn.prepareStatement(
        "INSERT INTO sos_events(id, child_id, lat, lon, created_at, acknowledged) " +
            "VALUES (?, ?, ?, ?, ?, 0)",
    ).use { statement ->
        statement.setString(1, id.toString())
        statement.setString(2, childId.toString())
        statement.setDouble(3, lat)
        statement.setDouble(4, lon)
        statement.setLong(5, createdAt)
        statement.executeUpdate()
    }
    return SosEvent(id, childId, lat, lon, createdAt, acknowledged = false)
}

fun ackSos(conn: Connection, eventId: UUID, familyId: UUID): Boolean =
    conn.prepareStatement(
        "UPDATE sos_events SET acknowledged = 1 " +
            "WHERE id = ? AND child_id IN (SELECT id FROM devices WHERE family_id = ?)",
    ).use { statement ->
        statement.setString(1, eventId.toString())
        statement.setString(2, familyId.toString())
        statement.executeUpdate() > 0
    }

fun listSos(conn: Connection, familyId: UUID, since: Long): List<SosEvent> =
    conn.prepareStatement(
        """
        SELECT s.id AS id, s.child_id AS child_id, s.lat AS lat, s.lon AS lon,
               s.created_at AS created_at, s.acknowledged AS acknowledged
        FROM sos_events s
        JOIN devices d ON d.id = s.child_id
        WHERE d.family_id = ? AND s.created_at >= ?
        ORDER BY s.created_at DESC, s.id ASC
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, familyId.toString())
        statement.setLong(2, since)
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    add(
                        SosEvent(
                            id = UUID.fromString(result.getString("id")),
                            childId = UUID.fromString(result.getString("child_id")),
                            lat = result.getDouble("lat"),
                            lon = result.getDouble("lon"),
                            createdAt = result.getLong("created_at"),
                            acknowledged = result.getInt("acknowledged") != 0,
                        ),
                    )
                }
            }
        }
    }

fun deleteOldLocations(conn: Connection, cutoffMs: Long): Int =
    conn.prepareStatement("DELETE FROM locations WHERE recorded_at < ?").use { statement ->
        statement.setLong(1, cutoffMs)
        statement.executeUpdate()
    }

private fun ResultSet.readPoint(): PointIn = PointIn(
    lat = getDouble("lat"),
    lon = getDouble("lon"),
    accuracy = nullableFloat("accuracy"),
    speed = nullableFloat("speed"),
    recordedAt = getLong("recorded_at"),
)

private fun ResultSet.nullableFloat(column: String): Float? {
    val value = getFloat(column)
    return if (wasNull()) null else value
}

private inline fun <T> inTransaction(conn: Connection, block: () -> T): T {
    if (!conn.autoCommit) return block()
    conn.autoCommit = false
    return try {
        val result = block()
        conn.commit()
        result
    } catch (failure: Throwable) {
        conn.rollback()
        throw failure
    } finally {
        conn.autoCommit = true
    }
}
