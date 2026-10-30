package dev.diamondecon.bank

import dev.diamondecon.getUuid
import dev.diamondecon.query
import dev.diamondecon.queryOne
import dev.diamondecon.update
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Diamond-backed accounts. Every balance change goes through a SQL transaction
 * and is written to the ledger, so balances can always be explained.
 */
class Bank(private val c: Connection) {

    enum class TxType { DEPOSIT, WITHDRAW, PAY, SHOP_PURCHASE }

    data class Account(val uuid: UUID, val name: String, val balance: Long)

    data class Txn(
        val createdAt: Long, val type: TxType,
        val from: UUID?, val fromName: String?, val to: UUID?, val toName: String?,
        val amount: Long, val item: String?, val itemAmount: Int,
    )

    data class Stats(
        val deposited: Long, val withdrawn: Long, val sent: Long, val received: Long,
        val shopIncome: Long, val shopSpent: Long, val count: Int,
    )

    fun ensureAccount(id: UUID, name: String) {
        c.update(
            "INSERT INTO accounts(uuid, name) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name",
            id, name,
        )
    }

    fun balance(id: UUID): Long =
        c.queryOne("SELECT balance FROM accounts WHERE uuid = ?", id) { it.getLong(1) } ?: 0

    fun findByName(name: String): Account? =
        c.queryOne("SELECT uuid, name, balance FROM accounts WHERE name = ? COLLATE NOCASE LIMIT 1", name, map = ::account)

    fun top(limit: Int): List<Account> =
        c.query("SELECT uuid, name, balance FROM accounts WHERE balance > 0 ORDER BY balance DESC LIMIT ?", limit, map = ::account)

    fun deposit(id: UUID, amount: Long) {
        atomically {
            credit(id, amount)
            log(TxType.DEPOSIT, null, id, amount)
            true
        }
    }

    /** @return false if the balance is too low */
    fun withdraw(id: UUID, amount: Long): Boolean = atomically {
        if (!debit(id, amount)) return@atomically false
        log(TxType.WITHDRAW, id, null, amount)
        true
    }

    /** @return false if the sender's balance is too low */
    fun transfer(
        from: UUID, to: UUID, amount: Long, type: TxType,
        item: String? = null, itemAmount: Int? = null, shopId: Long? = null,
    ): Boolean = atomically {
        if (!debit(from, amount)) return@atomically false
        credit(to, amount)
        log(type, from, to, amount, item, itemAmount, shopId)
        true
    }

    fun history(id: UUID, limit: Int, offset: Int): List<Txn> = c.query(
        """
        SELECT t.created_at, t.type, t.from_uuid, f.name, t.to_uuid, r.name, t.amount, t.item, t.item_amount
        FROM transactions t
        LEFT JOIN accounts f ON f.uuid = t.from_uuid
        LEFT JOIN accounts r ON r.uuid = t.to_uuid
        WHERE t.from_uuid = ?1 OR t.to_uuid = ?1
        ORDER BY t.id DESC LIMIT ?2 OFFSET ?3""",
        id, limit, offset,
    ) { rs ->
        Txn(
            rs.getLong(1), TxType.valueOf(rs.getString(2)),
            rs.getUuid(3), rs.getString(4), rs.getUuid(5), rs.getString(6),
            rs.getLong(7), rs.getString(8), rs.getInt(9),
        )
    }

    fun stats(id: UUID): Stats = c.queryOne(
        """
        SELECT
          COALESCE(SUM(CASE WHEN type = 'DEPOSIT'       AND to_uuid   = ?1 THEN amount END), 0),
          COALESCE(SUM(CASE WHEN type = 'WITHDRAW'      AND from_uuid = ?1 THEN amount END), 0),
          COALESCE(SUM(CASE WHEN type = 'PAY'           AND from_uuid = ?1 THEN amount END), 0),
          COALESCE(SUM(CASE WHEN type = 'PAY'           AND to_uuid   = ?1 THEN amount END), 0),
          COALESCE(SUM(CASE WHEN type = 'SHOP_PURCHASE' AND to_uuid   = ?1 THEN amount END), 0),
          COALESCE(SUM(CASE WHEN type = 'SHOP_PURCHASE' AND from_uuid = ?1 THEN amount END), 0),
          COUNT(*)
        FROM transactions WHERE from_uuid = ?1 OR to_uuid = ?1""",
        id,
    ) { rs ->
        Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getInt(7))
    }!!

    private fun debit(id: UUID, amount: Long): Boolean =
        c.update("UPDATE accounts SET balance = balance - ?1 WHERE uuid = ?2 AND balance >= ?1", amount, id) == 1

    private fun credit(id: UUID, amount: Long) {
        if (c.update("UPDATE accounts SET balance = balance + ? WHERE uuid = ?", amount, id) != 1) {
            throw SQLException("No account for $id")
        }
    }

    private fun log(
        type: TxType, from: UUID?, to: UUID?, amount: Long,
        item: String? = null, itemAmount: Int? = null, shopId: Long? = null,
    ) {
        c.update(
            """
            INSERT INTO transactions(created_at, type, from_uuid, to_uuid, amount, item, item_amount, shop_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            System.currentTimeMillis(), type.name, from, to, amount, item, itemAmount, shopId,
        )
    }

    /** Runs [work] in a SQL transaction; commits if it returns true, rolls back otherwise. */
    private inline fun atomically(work: () -> Boolean): Boolean {
        c.autoCommit = false
        try {
            val ok = work()
            if (ok) c.commit() else c.rollback()
            return ok
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    private fun account(rs: ResultSet) = Account(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3))
}
