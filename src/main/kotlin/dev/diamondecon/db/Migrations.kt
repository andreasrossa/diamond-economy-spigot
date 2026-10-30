package dev.diamondecon.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger

/**
 * Versioned schema migrations, applied on startup. The plugin owns the schema; the dashboard only reads it.
 *
 * Each script in `src/main/resources/db/migrations/` runs once, in order, in its own transaction (Postgres DDL is
 * transactional, so a failing script leaves nothing behind), and is recorded in `schema_version`.
 * Never edit a script that has shipped: add a new one to [SCRIPTS] instead, and update [Tables] to match.
 */
object Migrations {

    /** Applied in order; a script's version is its position, starting at 1. */
    internal val SCRIPTS = listOf(
        "001_initial_schema.sql",
    )

    /** Applies pending migrations. Throws if the database is newer than this plugin or a script fails. */
    fun run(db: Database, logger: Logger) {
        val current = transaction(db) {
            jdbc().createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version    INT PRIMARY KEY,
                        script     TEXT NOT NULL,
                        applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )"""
                )
                st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { it.next(); it.getInt(1) }
            }
        }
        check(current <= SCRIPTS.size) {
            "The database schema is at version $current, but this plugin only knows up to ${SCRIPTS.size}. Update the plugin."
        }

        SCRIPTS.forEachIndexed { i, script ->
            val version = i + 1
            if (version <= current) return@forEachIndexed
            val sql = Migrations::class.java.getResourceAsStream("/db/migrations/$script")
                ?.use { it.readBytes().decodeToString() }
                ?: error("Migration $script is missing from the plugin jar")
            transaction(db) {
                val c = jdbc()
                // Serializes concurrent startups; a second server waits here, then sees the version as applied.
                c.createStatement().use { it.execute("LOCK TABLE schema_version IN EXCLUSIVE MODE") }
                val applied = c.prepareStatement("SELECT 1 FROM schema_version WHERE version = ?").use { ps ->
                    ps.setInt(1, version)
                    ps.executeQuery().use { it.next() }
                }
                if (applied) return@transaction
                logger.info("Applying database migration $script")
                try {
                    c.createStatement().use { it.execute(sql) }
                } catch (e: SQLException) {
                    throw IllegalStateException("Database migration $script failed, nothing from it was applied", e)
                }
                c.prepareStatement("INSERT INTO schema_version(version, script) VALUES (?, ?)").use { ps ->
                    ps.setInt(1, version)
                    ps.setString(2, script)
                    ps.executeUpdate()
                }
            }
        }

        // Catches Tables.kt changes that have no matching migration (or the other way round).
        val drift = transaction(db) { SchemaUtils.statementsRequiredToActualizeScheme(Accounts, Transactions, Shops) }
        if (drift.isNotEmpty()) {
            logger.warning("The database schema doesn't match Tables.kt. A migration is probably missing:")
            drift.forEach { logger.warning("  $it") }
        }
    }

    /** The raw JDBC connection, for running multi-statement scripts. */
    private fun JdbcTransaction.jdbc() = connection.connection as Connection
}
