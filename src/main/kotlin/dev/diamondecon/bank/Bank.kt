package dev.diamondecon.bank

import dev.diamondecon.db.Accounts
import dev.diamondecon.db.Transactions
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.sql.SQLException
import java.util.UUID

/**
 * Diamond-backed accounts. Every balance change goes through a SQL transaction
 * and is written to the ledger, so balances can always be explained.
 */
class Bank(private val db: Database) {

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
        transaction(db) {
            Accounts.upsert(onUpdate = { it[Accounts.name] = insertValue(Accounts.name) }) {
                it[uuid] = id
                it[Accounts.name] = name
            }
        }
    }

    fun balance(id: UUID): Long = transaction(db) {
        Accounts.select(Accounts.balance).where { Accounts.uuid eq id }.singleOrNull()?.get(Accounts.balance) ?: 0
    }

    fun findByName(name: String): Account? = transaction(db) {
        Accounts.selectAll().where { Accounts.name.lowerCase() eq name.lowercase() }.limit(1).singleOrNull()?.let(::account)
    }

    fun top(limit: Int): List<Account> = transaction(db) {
        Accounts.selectAll().where { Accounts.balance greater 0L }
            .orderBy(Accounts.balance, SortOrder.DESC).limit(limit).map(::account)
    }

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

    fun history(id: UUID, limit: Int, offset: Int): List<Txn> = transaction(db) {
        val f = Accounts.alias("f")
        val r = Accounts.alias("r")
        Transactions
            .join(f, JoinType.LEFT, Transactions.fromUuid, f[Accounts.uuid])
            .join(r, JoinType.LEFT, Transactions.toUuid, r[Accounts.uuid])
            .selectAll()
            .where { (Transactions.fromUuid eq id) or (Transactions.toUuid eq id) }
            .orderBy(Transactions.id, SortOrder.DESC)
            .limit(limit).offset(offset.toLong())
            .map { row ->
                Txn(
                    row[Transactions.createdAt].toInstant().toEpochMilli(), row[Transactions.type],
                    row[Transactions.fromUuid], row.getOrNull(f[Accounts.name]),
                    row[Transactions.toUuid], row.getOrNull(r[Accounts.name]),
                    row[Transactions.amount], row[Transactions.item], row[Transactions.itemAmount] ?: 0,
                )
            }
    }

    fun stats(id: UUID): Stats = transaction(db) {
        val incoming = Transactions.toUuid eq id
        val outgoing = Transactions.fromUuid eq id
        fun total(type: TxType, side: Op<Boolean>) =
            Sum(case().When((Transactions.type eq type) and side, Transactions.amount), LongColumnType())

        val deposited = total(TxType.DEPOSIT, incoming)
        val withdrawn = total(TxType.WITHDRAW, outgoing)
        val sent = total(TxType.PAY, outgoing)
        val received = total(TxType.PAY, incoming)
        val shopIncome = total(TxType.SHOP_PURCHASE, incoming)
        val shopSpent = total(TxType.SHOP_PURCHASE, outgoing)
        val count = Transactions.id.count()

        val row = Transactions.select(deposited, withdrawn, sent, received, shopIncome, shopSpent, count)
            .where { incoming or outgoing }
            .single()
        Stats(
            row[deposited] ?: 0, row[withdrawn] ?: 0, row[sent] ?: 0, row[received] ?: 0,
            row[shopIncome] ?: 0, row[shopSpent] ?: 0, row[count].toInt(),
        )
    }

    /** Conditional debit: matches no row (and changes nothing) if the balance is too low. */
    private fun debit(id: UUID, amount: Long): Boolean =
        Accounts.update({ (Accounts.uuid eq id) and (Accounts.balance greaterEq amount) }) {
            it[balance] = balance - amount
        } == 1

    private fun credit(id: UUID, amount: Long) {
        val updated = Accounts.update({ Accounts.uuid eq id }) { it[balance] = balance + amount }
        if (updated != 1) throw SQLException("No account for $id")
    }

    private fun log(
        type: TxType, from: UUID?, to: UUID?, amount: Long,
        item: String? = null, itemAmount: Int? = null, shopId: Long? = null,
    ) {
        Transactions.insert {
            it[Transactions.type] = type
            it[fromUuid] = from
            it[toUuid] = to
            it[Transactions.amount] = amount
            it[Transactions.item] = item
            it[Transactions.itemAmount] = itemAmount
            it[Transactions.shopId] = shopId
        }
    }

    /** Runs [work] in a SQL transaction; commits if it returns true, rolls back otherwise. */
    private fun atomically(work: JdbcTransaction.() -> Boolean): Boolean = transaction(db) {
        work().also { ok -> if (!ok) rollback() }
    }

    private fun account(row: ResultRow) = Account(row[Accounts.uuid], row[Accounts.name], row[Accounts.balance])
}
