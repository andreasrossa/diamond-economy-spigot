package dev.diamondecon.db

import org.bukkit.configuration.MemoryConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageTest {

    private fun settings(vararg values: Pair<String, Any>) = Storage.connectionSettings(
        MemoryConfiguration().apply { values.forEach { (key, value) -> set(key, value) } }
    )

    @Test
    fun `defaults match docker-compose`() {
        assertEquals(
            Storage.ConnectionSettings("jdbc:postgresql://localhost:5432/diamondecon", "diamondecon", "diamondecon"),
            settings(),
        )
    }

    @Test
    fun `host, port and name are used`() {
        assertEquals(
            "jdbc:postgresql://ep-cool-name-123.eu-central-1.aws.neon.tech:5432/neondb",
            settings("host" to "ep-cool-name-123.eu-central-1.aws.neon.tech", "name" to "neondb").jdbcUrl,
        )
    }

    @Test
    fun `ssl-mode is added when set, and blank means the driver default`() {
        assertEquals("jdbc:postgresql://localhost:5432/diamondecon?sslmode=require", settings("ssl-mode" to "Require ").jdbcUrl)
        assertEquals("jdbc:postgresql://localhost:5432/diamondecon", settings("ssl-mode" to "").jdbcUrl)
    }

    @Test
    fun `an unknown ssl-mode is refused`() {
        val e = assertFailsWith<IllegalArgumentException> { settings("ssl-mode" to "yes") }
        assertTrue("yes" in e.message!!)
    }

    @Test
    fun `a Neon connection string is split into JDBC URL and credentials`() {
        val neon = "postgresql://neondb_owner:npg_AbC123@ep-cool-name-123.eu-central-1.aws.neon.tech/neondb" +
            "?sslmode=require&channel_binding=require"
        assertEquals(
            Storage.ConnectionSettings(
                "jdbc:postgresql://ep-cool-name-123.eu-central-1.aws.neon.tech/neondb?sslmode=require&channelBinding=require",
                "neondb_owner",
                "npg_AbC123",
            ),
            settings("url" to neon, "host" to "ignored", "user" to "ignored", "password" to "ignored"),
        )
    }

    @Test
    fun `percent-encoded credentials are decoded`() {
        val s = settings("url" to "postgres://app%40team:p%40ss%3Aw%2Frd@db.example.com:6543/eco")
        assertEquals("jdbc:postgresql://db.example.com:6543/eco", s.jdbcUrl)
        assertEquals("app@team", s.user)
        assertEquals("p@ss:w/rd", s.password)
    }

    @Test
    fun `a URL without credentials uses user and password, and gets ssl-mode unless it sets its own`() {
        val s = settings("url" to "jdbc:postgresql://db.example.com/eco", "user" to "u", "password" to "p", "ssl-mode" to "require")
        assertEquals(Storage.ConnectionSettings("jdbc:postgresql://db.example.com/eco?sslmode=require", "u", "p"), s)
        assertEquals(
            "jdbc:postgresql://db.example.com/eco?sslmode=disable",
            settings("url" to "postgresql://db.example.com/eco?sslmode=disable", "ssl-mode" to "require").jdbcUrl,
        )
    }

    @Test
    fun `bad URLs are refused with a clear message`() {
        assertTrue("must start with" in assertFailsWith<IllegalArgumentException> { settings("url" to "mysql://h/db") }.message!!)
        assertTrue("no database name" in assertFailsWith<IllegalArgumentException> { settings("url" to "postgresql://h") }.message!!)
    }

    @Test
    fun `the password never shows up in logs`() {
        assertFalse("npg_AbC123" in settings("url" to "postgresql://u:npg_AbC123@h/db").toString())
    }
}
