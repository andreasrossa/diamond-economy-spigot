package dev.diamondecon.db

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationsTest {

    private val allApplied = Migrations.SCRIPTS.mapIndexed { i, script -> "${i + 1}:$script" }

    private fun versions(db: String) = TestPostgres.sql(db, "SELECT version || ':' || script FROM schema_version ORDER BY version")

    @Test
    fun `every script on disk is listed in order, and every listed script exists`() {
        val onDisk = File("src/main/resources/db/migrations").list()!!.filter { it.endsWith(".sql") }.sorted()
        assertEquals(onDisk, Migrations.SCRIPTS, "Migrations.SCRIPTS must list every file in db/migrations, in order")
        Migrations.SCRIPTS.forEachIndexed { i, script ->
            assertEquals(i + 1, script.substringBefore('_').toInt(), "$script should be numbered ${i + 1}")
        }
    }

    @Test
    fun `fresh database gets every migration and matches Tables kt`() {
        val db = TestPostgres.newDatabase()
        val log = LogCapture()
        TestPostgres.storage(db, log.logger).use { storage ->
            assertEquals(allApplied, versions(db))
            assertEquals(
                Migrations.SCRIPTS.map { "Applying database migration $it" },
                log.messages(Level.INFO).filter { it.startsWith("Applying") },
            )
            val drift = transaction(storage.db) {
                SchemaUtils.statementsRequiredToActualizeScheme(Accounts, Transactions, Shops)
            }
            assertEquals(emptyList(), drift, "The migrations and Tables.kt disagree")
            assertEquals(emptyList(), log.messages(Level.WARNING))
        }
    }

    @Test
    fun `second startup applies nothing`() {
        val db = TestPostgres.newDatabase()
        TestPostgres.storage(db).close()
        val log = LogCapture()
        TestPostgres.storage(db, log.logger).close()
        assertEquals(allApplied, versions(db))
        assertTrue(log.records.none { it.message.startsWith("Applying") })
    }

    @Test
    fun `refuses a database that is newer than the plugin`() {
        val db = TestPostgres.newDatabase()
        TestPostgres.storage(db).close()
        val future = Migrations.SCRIPTS.size + 1
        TestPostgres.sql(db, "INSERT INTO schema_version(version, script) VALUES ($future, 'future.sql')")

        val e = assertFailsWith<IllegalStateException> { TestPostgres.storage(db) }
        assertTrue("at version $future" in e.message!!, e.message)
    }

    @Test
    fun `a failing migration leaves nothing behind`() {
        // A database whose tables predate migrations: the first script fails on "accounts already exists".
        val db = TestPostgres.newDatabase()
        TestPostgres.sql(db, "CREATE TABLE accounts (uuid uuid PRIMARY KEY)")

        val e = assertFailsWith<IllegalStateException> { TestPostgres.storage(db) }
        assertTrue(Migrations.SCRIPTS.first() in e.message!!, e.message)
        assertEquals(listOf("0"), TestPostgres.sql(db, "SELECT count(*) FROM schema_version"))
        assertEquals(
            listOf("0"),
            TestPostgres.sql(db, "SELECT count(*) FROM information_schema.tables WHERE table_name IN ('transactions', 'shops')"),
        )
    }

    @Test
    fun `warns when the database and Tables kt disagree`() {
        val db = TestPostgres.newDatabase()
        TestPostgres.storage(db).close()
        TestPostgres.sql(db, "ALTER TABLE shops DROP COLUMN icon")

        val log = LogCapture()
        TestPostgres.storage(db, log.logger).close()
        val warnings = log.messages(Level.WARNING)
        assertTrue(warnings.any { "shops" in it && "icon" in it }, warnings.toString())
    }
}
