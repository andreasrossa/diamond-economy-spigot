package dev.diamondecon.db

import org.bukkit.configuration.MemoryConfiguration
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * One postgres:16 container (same image as docker-compose.yml) shared by the whole test run.
 * Each test gets its own empty database in it, so tests can't see each other's data.
 */
object TestPostgres {

    private val container = PostgreSQLContainer("postgres:16").apply { start() }
    private val counter = AtomicInteger()

    /** Creates an empty database and returns its name. */
    fun newDatabase(): String {
        val name = "test_${counter.incrementAndGet()}"
        sql(container.databaseName, "CREATE DATABASE $name")
        return name
    }

    /** Opens [Storage] on [database], which runs the migrations. */
    fun storage(database: String, logger: Logger = LogCapture().logger): Storage =
        Storage(MemoryConfiguration().apply {
            set("host", container.host)
            set("port", container.firstMappedPort)
            set("name", database)
            set("user", container.username)
            set("password", container.password)
        }, logger)

    /** Runs raw SQL on [database]; returns the first column of each row, if the statement returns rows. */
    fun sql(database: String, statement: String): List<String?> =
        DriverManager.getConnection(
            "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$database",
            container.username, container.password,
        ).use { c ->
            c.createStatement().use { st ->
                if (!st.execute(statement)) return emptyList()
                st.resultSet.use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
}

/** A logger that records what it is given, so tests can check what the plugin logged. */
class LogCapture : Handler() {
    val records = mutableListOf<LogRecord>()
    val logger: Logger = Logger.getAnonymousLogger().also {
        it.useParentHandlers = false
        it.addHandler(this)
    }

    fun messages(level: Level): List<String> = records.filter { it.level == level }.map { it.message }

    override fun publish(record: LogRecord) {
        records += record
    }

    override fun flush() {}
    override fun close() {}
}
