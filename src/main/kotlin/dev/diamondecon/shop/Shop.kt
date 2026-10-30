package dev.diamondecon.shop

import dev.diamondecon.util.prettyName
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** A chest/barrel that sells [quantity] of [item] for [price] diamonds. */
class Shop(
    val id: Long,
    owner: UUID,
    ownerName: String,
    val key: BlockKey,
    val item: ItemStack,
    name: String?,
    val quantity: Int,
    price: Long,
    stock: Int,
) {
    /** Custom name set by the owner, or null. */
    var name = name
        internal set
    /** Item shown floating above the shop instead of [item], or null. Cosmetic only; purchases always use [item]. */
    var icon: ItemStack? = null
        internal set

    /** The item floating above the shop. */
    val displayIcon: ItemStack get() = icon ?: item

    /** What players see: the custom name if there is one, otherwise the item's own name. */
    val displayName: String get() = name ?: item.prettyName

    var owner = owner
        internal set
    var ownerName = ownerName
        internal set
    var price = price
        internal set
    var stock = stock
        internal set

    // Decorations are non-persistent and respawned whenever the chunk loads.
    internal var itemDisplay: ItemDisplay? = null
    internal var label: TextDisplay? = null
    internal var angle = 0f
}
