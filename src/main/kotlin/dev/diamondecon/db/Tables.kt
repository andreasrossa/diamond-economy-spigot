package dev.diamondecon.db

import dev.diamondecon.bank.Bank
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// The schema is read directly by the dashboard, so treat column names and types as a public interface.

object Accounts : Table("accounts") {
    val uuid = javaUUID("uuid")
    val name = text("name")
    val balance = long("balance").default(0).check { it greaterEq 0L }

    override val primaryKey = PrimaryKey(uuid)

    init {
        // Case-insensitive name lookups (/pay, /balance <player>).
        index("accounts_name_lower_idx", false, functions = listOf(name.lowerCase()))
    }
}

/** The ledger: one row per balance change. */
object Transactions : LongIdTable("transactions") {
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val type = enumerationByName<Bank.TxType>("type", 16)
    val fromUuid = javaUUID("from_uuid").references(Accounts.uuid).nullable()
    val toUuid = javaUUID("to_uuid").references(Accounts.uuid).nullable()
    val amount = long("amount").check { it greater 0L }
    /** Material name of the item bought. */
    val item = text("item").nullable()
    /** The shop's name as the buyer saw it (custom name or item name, may contain § color codes). */
    val itemName = text("item_name").nullable()
    val itemAmount = integer("item_amount").nullable()
    // No foreign key: removing a shop keeps its sales in the ledger.
    val shopId = long("shop_id").nullable()

    init {
        index(false, fromUuid, createdAt)
        index(false, toUuid, createdAt)
    }
}

object Shops : LongIdTable("shops") {
    val ownerUuid = javaUUID("owner_uuid").references(Accounts.uuid)
    val ownerName = text("owner_name")
    val world = text("world")
    val x = integer("x")
    val y = integer("y")
    val z = integer("z")
    /** YAML-serialized ItemStack. */
    val item = text("item")
    /** Material name, for the dashboard. */
    val itemType = text("item_type").index()
    /** Optional name chosen by the owner (may contain § color codes); null means "use the item's name". */
    val displayName = text("display_name").nullable()
    /** YAML-serialized ItemStack shown floating above the shop instead of [item]; null means [item]. Cosmetic only. */
    val icon = text("icon").nullable()
    val quantity = integer("quantity").check { it greater 0 }
    val price = long("price").check { it greater 0L }
    val stock = integer("stock").default(0)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)

    init {
        uniqueIndex(world, x, y, z)
    }
}
