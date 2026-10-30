package dev.diamondecon.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.configuration.ConfigurationSection
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.logging.Logger

/**
 * Postgres connection pool plus the Exposed [Database] on top of it. Applies pending [Migrations] on startup.
 * Settings come from the `database` section of config.yml.
 */
class Storage(config: ConfigurationSection, logger: Logger) : AutoCloseable {

    private val dataSource = HikariDataSource(HikariConfig().apply {
        poolName = "DiamondEcon"
        // Set explicitly: the driver lives in Spigot's library classloader, not the system one.
        driverClassName = "org.postgresql.Driver"
        jdbcUrl = jdbcUrl(config)
        username = config.getString("user", "diamondecon")
        password = config.getString("password", "diamondecon")
        maximumPoolSize = config.getInt("pool-size", 4)
        // Idle connections keep a hosted database such as Neon awake (and billed). With min-idle 0 they close
        // after idle-timeout-seconds, so the database can pause; the next query wakes it up.
        minimumIdle = config.getInt("min-idle", maximumPoolSize)
        idleTimeout = config.getLong("idle-timeout-seconds", 600) * 1000
        // Fail fast: a database job waits at most this long for a connection before it reports an error.
        connectionTimeout = 5_000
    })

    val db: Database = try {
        Database.connect(dataSource, databaseConfig = DatabaseConfig {
            // No silent retries: a retried job could run after work queued behind it. Callers report failures.
            defaultMaxAttempts = 1
        }).also { db -> Migrations.run(db, logger) }
    } catch (e: Exception) {
        dataSource.close()
        throw e
    }

    override fun close() = dataSource.close()

    companion object {
        private val SSL_MODES = setOf("disable", "allow", "prefer", "require", "verify-ca", "verify-full")

        /** Builds the JDBC URL from host, port, name and the optional ssl-mode (Neon needs `require`). */
        internal fun jdbcUrl(config: ConfigurationSection): String {
            val url = "jdbc:postgresql://${config.getString("host", "localhost")}:${config.getInt("port", 5432)}" +
                "/${config.getString("name", "diamondecon")}"
            val sslMode = config.getString("ssl-mode")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return url
            require(sslMode in SSL_MODES) { "database.ssl-mode must be one of $SSL_MODES, not '$sslMode'" }
            return "$url?sslmode=$sslMode"
        }
    }
}
