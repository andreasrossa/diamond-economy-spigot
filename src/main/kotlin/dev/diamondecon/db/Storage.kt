package dev.diamondecon.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.configuration.ConfigurationSection
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.net.URI
import java.net.URISyntaxException
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
        val connection = connectionSettings(config)
        jdbcUrl = connection.jdbcUrl
        username = connection.user
        password = connection.password
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

    /** Where and as whom to connect. [jdbcUrl] never contains the credentials. */
    internal data class ConnectionSettings(val jdbcUrl: String, val user: String, val password: String) {
        override fun toString() = "ConnectionSettings(jdbcUrl=$jdbcUrl, user=$user, password=***)"
    }

    companion object {
        private val SSL_MODES = setOf("disable", "allow", "prefer", "require", "verify-ca", "verify-full")

        /**
         * Reads the connection from config.yml: either `url`, a connection string such as Neon's
         * `postgresql://user:password@host/db?sslmode=require` (or a `jdbc:postgresql://` URL), or else the
         * separate host, port, name, user and password keys. `ssl-mode` applies unless the URL sets its own.
         */
        internal fun connectionSettings(config: ConfigurationSection): ConnectionSettings {
            val sslMode = config.getString("ssl-mode")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            require(sslMode == null || sslMode in SSL_MODES) {
                "database.ssl-mode must be one of $SSL_MODES, not '$sslMode'"
            }
            val user = config.getString("user", "diamondecon")!!
            val password = config.getString("password", "diamondecon")!!

            val url = config.getString("url")?.trim()?.takeIf { it.isNotEmpty() }
            if (url == null) {
                val jdbcUrl = "jdbc:postgresql://${config.getString("host", "localhost")}:${config.getInt("port", 5432)}" +
                    "/${config.getString("name", "diamondecon")}"
                return ConnectionSettings(withParam(jdbcUrl, "sslmode", sslMode), user, password)
            }
            return fromUrl(url, sslMode, user, password)
        }

        private fun fromUrl(url: String, sslMode: String?, defaultUser: String, defaultPassword: String): ConnectionSettings {
            val uri = try {
                URI(url.removePrefix("jdbc:"))
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("database.url is not a valid connection string", e)
            }
            require(uri.scheme in setOf("postgres", "postgresql")) {
                "database.url must start with postgresql://, postgres:// or jdbc:postgresql://"
            }
            val host = requireNotNull(uri.host) { "database.url has no host" }
            val database = uri.path?.removePrefix("/")?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("database.url has no database name")

            // Credentials go to the pool separately; getUserInfo() has already decoded %-escapes.
            val userInfo = uri.userInfo
            val user = userInfo?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: defaultUser
            val password = if (userInfo != null && ':' in userInfo) userInfo.substringAfter(':') else defaultPassword

            // libpq parameter names (as in Neon's strings) differ from the JDBC driver's for channel binding.
            val params = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }
                ?.map { if (it.startsWith("channel_binding=")) "channelBinding=" + it.substringAfter('=') else it }
                .orEmpty()
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val jdbcUrl = "jdbc:postgresql://$host$port/$database" +
                if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
            val withSsl = if (params.any { it.startsWith("sslmode=") }) jdbcUrl else withParam(jdbcUrl, "sslmode", sslMode)
            return ConnectionSettings(withSsl, user, password)
        }

        private fun withParam(url: String, name: String, value: String?): String = when {
            value == null -> url
            '?' in url -> "$url&$name=$value"
            else -> "$url?$name=$value"
        }
    }
}
