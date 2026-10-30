package dev.diamondecon.util

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

// Counting, removing and adding items by "similarity" (same type and meta, any amount).

fun Inventory.countItems(proto: ItemStack): Int =
    storageContents.filterNotNull().filter { it.isSimilar(proto) }.sumOf { it.amount }

/** How many more of [proto] fit into the inventory. */
fun Inventory.roomFor(proto: ItemStack): Int {
    val max = minOf(proto.maxStackSize, maxStackSize)
    return storageContents.sumOf { s ->
        when {
            s == null || s.type.isAir -> max
            s.isSimilar(proto) -> maxOf(0, max - s.amount)
            else -> 0
        }
    }
}

/** Removes up to [amount]; callers should check [countItems] first. */
fun Inventory.takeItems(proto: ItemStack, amount: Int) {
    var left = amount
    val contents = storageContents
    for (i in contents.indices) {
        if (left == 0) break
        val s = contents[i] ?: continue
        if (!s.isSimilar(proto)) continue
        val take = minOf(left, s.amount)
        left -= take
        if (take == s.amount) contents[i] = null else s.amount -= take
    }
    storageContents = contents
}

/** Adds [amount]; callers should check [roomFor] first. Returns how many did not fit. */
fun Inventory.giveItems(proto: ItemStack, amount: Int): Int {
    var left = amount
    var leftover = 0
    while (left > 0) {
        val stack = proto.clone().apply { this.amount = minOf(proto.maxStackSize, left) }
        left -= stack.amount
        leftover += addItem(stack).values.sumOf { it.amount }
    }
    return leftover
}
