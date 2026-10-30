package dev.diamondecon.bank

import dev.diamondecon.bank.Bank.TxType
import dev.diamondecon.db.Storage
import dev.diamondecon.db.TestPostgres
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BankTest {

    private lateinit var db: String
    private lateinit var storage: Storage
    private lateinit var bank: Bank

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        db = TestPostgres.newDatabase()
        storage = TestPostgres.storage(db)
        bank = Bank(storage.db)
        bank.ensureAccount(alice, "Alice")
        bank.ensureAccount(bob, "Bob")
    }

    @AfterTest
    fun tearDown() = storage.close()

    private fun ledgerRows() = TestPostgres.sql(db, "SELECT count(*) FROM transactions").single()!!.toInt()

    // --- accounts ----------------------------------------------------------------------------------

    @Test
    fun `new accounts start empty`() {
        assertEquals(0, bank.balance(alice))
    }

    @Test
    fun `unknown players have a zero balance`() {
        assertEquals(0, bank.balance(UUID.randomUUID()))
    }

    @Test
    fun `ensureAccount updates the name but keeps the balance`() {
        bank.deposit(alice, 50)
        bank.ensureAccount(alice, "Alicia")
        assertEquals(50, bank.balance(alice))
        assertEquals("Alicia", bank.findByName("Alicia")?.name)
        assertNull(bank.findByName("Alice"))
    }

    @Test
    fun `findByName ignores case`() {
        assertEquals(alice, bank.findByName("aLiCe")?.uuid)
        assertNull(bank.findByName("Nobody"))
    }

    @Test
    fun `top lists funded accounts, richest first, up to the limit`() {
        val carol = UUID.randomUUID().also { bank.ensureAccount(it, "Carol") }
        bank.deposit(alice, 5)
        bank.deposit(bob, 30)
        bank.deposit(carol, 10)
        assertEquals(listOf("Bob", "Carol"), bank.top(2).map { it.name })
        bank.withdraw(carol, 10)
        assertEquals(listOf("Bob", "Alice"), bank.top(10).map { it.name }, "Empty accounts are left out")
    }

    // --- balance changes ---------------------------------------------------------------------------

    @Test
    fun `deposit and withdraw move the balance and write the ledger`() {
        bank.deposit(alice, 100)
        assertTrue(bank.withdraw(alice, 30))
        assertEquals(70, bank.balance(alice))
        assertEquals(2, ledgerRows())
    }

    @Test
    fun `withdrawing more than the balance changes nothing`() {
        bank.deposit(alice, 10)
        assertFalse(bank.withdraw(alice, 11))
        assertEquals(10, bank.balance(alice))
        assertEquals(1, ledgerRows(), "A refused withdrawal must not be logged")
    }

    @Test
    fun `transfer moves money between accounts`() {
        bank.deposit(alice, 100)
        assertTrue(bank.transfer(alice, bob, 40, TxType.PAY))
        assertEquals(60, bank.balance(alice))
        assertEquals(40, bank.balance(bob))
    }

    @Test
    fun `transfer with too little balance changes nothing`() {
        bank.deposit(alice, 10)
        assertFalse(bank.transfer(alice, bob, 11, TxType.PAY))
        assertEquals(10, bank.balance(alice))
        assertEquals(0, bank.balance(bob))
        assertEquals(1, ledgerRows())
    }

    @Test
    fun `transfer to a missing account rolls back the debit`() {
        bank.deposit(alice, 10)
        assertFailsWith<SQLException> { bank.transfer(alice, UUID.randomUUID(), 5, TxType.PAY) }
        assertEquals(10, bank.balance(alice))
        assertEquals(1, ledgerRows())
    }

    @Test
    fun `the database rejects non-positive amounts`() {
        assertFailsWith<SQLException> { bank.deposit(alice, 0) }
        assertEquals(0, bank.balance(alice))
        assertEquals(0, ledgerRows())
    }

    @Test
    fun `concurrent withdrawals can't overdraw`() {
        bank.deposit(alice, 10)
        val pool = Executors.newFixedThreadPool(8)
        val results = try {
            pool.invokeAll(List(30) { Callable { bank.withdraw(alice, 1) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(10, results.count { it })
        assertEquals(0, bank.balance(alice))
        assertEquals(11, ledgerRows())
    }

    // --- history and stats -------------------------------------------------------------------------

    @Test
    fun `history is newest first with names and shop details`() {
        bank.deposit(alice, 100)
        bank.transfer(alice, bob, 20, TxType.PAY)
        bank.transfer(alice, bob, 5, TxType.SHOP_PURCHASE, "SHULKER_BOX", "§6Shulker of Dirt", 1, shopId = 7)

        val h = bank.history(alice, 10, 0)
        assertEquals(listOf(TxType.SHOP_PURCHASE, TxType.PAY, TxType.DEPOSIT), h.map { it.type })

        val purchase = h[0]
        assertEquals(alice to bob, purchase.from to purchase.to)
        assertEquals("Alice" to "Bob", purchase.fromName to purchase.toName)
        assertEquals("SHULKER_BOX", purchase.item)
        assertEquals("§6Shulker of Dirt", purchase.itemName)
        assertEquals(1, purchase.itemAmount)
        assertEquals(listOf("7"), TestPostgres.sql(db, "SELECT shop_id FROM transactions WHERE type = 'SHOP_PURCHASE'"))

        val deposit = h[2]
        assertNull(deposit.from)
        assertNull(deposit.fromName)
        assertEquals("Alice", deposit.toName)
        assertTrue(h[0].createdAt >= h[2].createdAt)
    }

    @Test
    fun `history pages and only shows the player's own transactions`() {
        bank.deposit(alice, 5)
        repeat(4) { bank.deposit(bob, 1) }
        assertEquals(1, bank.history(alice, 10, 0).size)
        assertEquals(listOf(4, 0), listOf(bank.history(bob, 10, 0).size, bank.history(bob, 10, 4).size))
        assertEquals(2, bank.history(bob, 2, 1).size)
    }

    @Test
    fun `stats add up each kind of transaction`() {
        bank.deposit(alice, 100)
        bank.withdraw(alice, 10)
        bank.transfer(alice, bob, 20, TxType.PAY)
        bank.deposit(bob, 50)
        bank.transfer(bob, alice, 7, TxType.PAY)
        bank.transfer(bob, alice, 3, TxType.SHOP_PURCHASE, "DIRT", "Dirt", 64, 1)
        bank.transfer(alice, bob, 4, TxType.SHOP_PURCHASE, "STONE", "Stone", 64, 2)

        assertEquals(
            Bank.Stats(deposited = 100, withdrawn = 10, sent = 20, received = 7, shopIncome = 3, shopSpent = 4, count = 6),
            bank.stats(alice),
        )
        assertEquals(Bank.Stats(0, 0, 0, 0, 0, 0, 0), bank.stats(UUID.randomUUID()))
    }
}
