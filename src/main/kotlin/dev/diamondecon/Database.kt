package dev.diamondecon

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.util.UUID

/**
 * Single SQLite connection, used only from the main server thread.
 * The schema is meant to be read directly by the future dashboard.
 */
class Database(file: File) : AutoCloseable {

    val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    init {
        connection.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA busy_timeout=5000")
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS accounts (
                    uuid    TEXT PRIMARY KEY,
                    name    TEXT NOT NULL,
                    balance INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0)
                )"""
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name COLLATE NOCASE)")
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS transactions (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at  INTEGER NOT NULL,
                    type        TEXT NOT NULL,
                    from_uuid   TEXT,
                    to_uuid     TEXT,
                    amount      INTEGER NOT NULL CHECK (amount > 0),
                    item        TEXT,
                    item_amount INTEGER,
                    shop_id     INTEGER
                )"""
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_from ON transactions(from_uuid, created_at)")
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_to ON transactions(to_uuid, created_at)")
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS shops (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    owner_uuid  TEXT NOT NULL,
                    owner_name  TEXT NOT NULL,
                    world       TEXT NOT NULL,
                    x           INTEGER NOT NULL,
                    y           INTEGER NOT NULL,
                    z           INTEGER NOT NULL,
                    item        TEXT NOT NULL,
                    item_type   TEXT NOT NULL,
                    quantity    INTEGER NOT NULL CHECK (quantity > 0),
                    price       INTEGER NOT NULL CHECK (price > 0),
                    stock       INTEGER NOT NULL DEFAULT 0,
                    created_at  INTEGER NOT NULL,
                    UNIQUE (world, x, y, z)
                )"""
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_shops_item ON shops(item_type)")
        }
    }

    override fun close() = connection.close()
}

/** Prepares [sql] with [params] bound in order. UUIDs are stored as strings. */
fun Connection.prepare(sql: String, vararg params: Any?, returnKeys: Boolean = false): PreparedStatement {
    val ps = if (returnKeys) prepareStatement(sql, Statement.RETURN_GENERATED_KEYS) else prepareStatement(sql)
    params.forEachIndexed { i, value ->
        when (value) {
            null -> ps.setNull(i + 1, Types.NULL)
            is UUID -> ps.setString(i + 1, value.toString())
            else -> ps.setObject(i + 1, value)
        }
    }
    return ps
}

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepare(sql, *params).use { it.executeUpdate() }

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepare(sql, *params).use { ps ->
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

/** Inserts a row and returns its generated id. */
fun Connection.insert(sql: String, vararg params: Any?): Long =
    prepare(sql, *params, returnKeys = true).use { ps ->
        ps.executeUpdate()
        ps.generatedKeys.use { it.next(); it.getLong(1) }
    }

fun ResultSet.getUuid(column: Int): UUID? = getString(column)?.let(UUID::fromString)
