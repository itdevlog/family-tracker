package familytracker.db

import java.sql.Connection
import java.sql.DriverManager

object Schema {
    private val statements = listOf(
        """
        CREATE TABLE IF NOT EXISTS families (
            id TEXT PRIMARY KEY,
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
            role TEXT NOT NULL CHECK (role IN ('parent', 'child')),
            name TEXT NOT NULL,
            token_hash TEXT NOT NULL UNIQUE,
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_devices_family ON devices(family_id)",
        """
        CREATE TABLE IF NOT EXISTS pair_codes (
            code TEXT PRIMARY KEY,
            family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
            expires_at INTEGER NOT NULL,
            used_by TEXT REFERENCES devices(id) DEFERRABLE INITIALLY DEFERRED
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_pair_codes_family ON pair_codes(family_id)",
        """
        CREATE TABLE IF NOT EXISTS locations (
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            accuracy REAL,
            speed REAL,
            recorded_at INTEGER NOT NULL,
            UNIQUE (device_id, recorded_at)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_locations_recorded_at ON locations(recorded_at)",
        """
        CREATE TABLE IF NOT EXISTS sos_events (
            id TEXT PRIMARY KEY,
            child_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            created_at INTEGER NOT NULL,
            acknowledged INTEGER NOT NULL DEFAULT 0 CHECK (acknowledged IN (0, 1))
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_sos_events_child_time ON sos_events(child_id, created_at)",
    )

    fun create(conn: Connection) {
        conn.createStatement().use { statement ->
            statements.forEach { statement.executeUpdate(it) }
        }
    }
}

/**
 * Owns the SQLite schema. [url] may be either a plain file path or a `jdbc:sqlite:` URL;
 * every connection is opened, configured and closed by [withConnection], so instances
 * are safe to share between Ktor request threads.
 */
class Database(url: String) {
    private val jdbcUrl = if (url.startsWith("jdbc:")) url else "jdbc:sqlite:$url"

    init {
        withConnection { conn ->
            conn.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode = WAL")
            }
            Schema.create(conn)
        }
    }

    fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
            }
            block(conn)
        }
}
