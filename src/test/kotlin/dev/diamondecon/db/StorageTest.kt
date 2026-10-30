package dev.diamondecon.db

import org.bukkit.configuration.MemoryConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StorageTest {

    private fun config(vararg values: Pair<String, Any>) = MemoryConfiguration().apply {
        values.forEach { (key, value) -> set(key, value) }
    }

    @Test
    fun `defaults match docker-compose`() {
        assertEquals("jdbc:postgresql://localhost:5432/diamondecon", Storage.jdbcUrl(config()))
    }

    @Test
    fun `host, port and name are used`() {
        assertEquals(
            "jdbc:postgresql://ep-cool-name-123.eu-central-1.aws.neon.tech:5432/neondb",
            Storage.jdbcUrl(config("host" to "ep-cool-name-123.eu-central-1.aws.neon.tech", "name" to "neondb")),
        )
    }

    @Test
    fun `ssl-mode is added when set, and blank means the driver default`() {
        assertEquals("jdbc:postgresql://localhost:5432/diamondecon?sslmode=require", Storage.jdbcUrl(config("ssl-mode" to "Require ")))
        assertEquals("jdbc:postgresql://localhost:5432/diamondecon", Storage.jdbcUrl(config("ssl-mode" to "")))
    }

    @Test
    fun `an unknown ssl-mode is refused`() {
        val e = assertFailsWith<IllegalArgumentException> { Storage.jdbcUrl(config("ssl-mode" to "yes")) }
        assertEquals(true, "yes" in e.message!!)
    }
}
