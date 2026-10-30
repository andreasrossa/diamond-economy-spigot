package dev.diamondecon.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.configuration.ConfigurationSection
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Postgres connection pool plus the Exposed [Database] on top of it. Creates missing tables on startup.
 * Settings come from the `database` section of config.yml.
 */
class Storage(config: ConfigurationSection) : AutoCloseable {

    private val dataSource = HikariDataSource(HikariConfig().apply {
        poolName = "DiamondEcon"
        // Set explicitly: the driver lives in Spigot's library classloader, not the system one.
        driverClassName = "org.postgresql.Driver"
        jdbcUrl = "jdbc:postgresql://${config.getString("host", "localhost")}:${config.getInt("port", 5432)}" +
            "/${config.getString("name", "diamondecon")}"
        username = config.getString("user", "diamondecon")
        password = config.getString("password", "diamondecon")
        maximumPoolSize = config.getInt("pool-size", 4)
        // Queries still run on the main thread, so fail fast instead of freezing the server.
        connectionTimeout = 5_000
    })

    val db: Database = try {
        Database.connect(dataSource, databaseConfig = DatabaseConfig {
            // No silent retries on the main thread; callers report the failure instead.
            defaultMaxAttempts = 1
        }).also { db ->
            transaction(db) {
                SchemaUtils.create(Accounts, Transactions, Shops)
                // create() skips existing tables, so add columns introduced after the first boot.
                exec("ALTER TABLE shops ADD COLUMN IF NOT EXISTS display_name text")
                exec("ALTER TABLE transactions ADD COLUMN IF NOT EXISTS item_name text")
                exec("ALTER TABLE shops ADD COLUMN IF NOT EXISTS icon text")
            }
        }
    } catch (e: Exception) {
        dataSource.close()
        throw e
    }

    override fun close() = dataSource.close()
}
