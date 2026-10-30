package dev.diamondecon.shop

import dev.diamondecon.bank.Bank
import dev.diamondecon.db.DbWorker
import dev.diamondecon.util.PREFIX
import dev.diamondecon.util.countItems
import dev.diamondecon.util.dia
import dev.diamondecon.util.giveOrDrop
import dev.diamondecon.util.roomFor
import dev.diamondecon.util.takeItems
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.type.Chest
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.logging.Level

/** Buying from shops, plus everything that keeps shop chests safe from non-owners. */
class ShopListener(
    private val plugin: JavaPlugin,
    private val shops: ShopManager,
    private val bank: Bank,
    private val worker: DbWorker,
) : Listener {

    private val lastClick = HashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        val shop = shops.at(e.clickedBlock ?: return) ?: return
        val p = e.player
        if (p.uniqueId == shop.owner) return // owners open their chest to restock

        when (e.action) {
            Action.RIGHT_CLICK_BLOCK -> {
                e.isCancelled = true
                val now = System.currentTimeMillis()
                val last = lastClick.put(p.uniqueId, now)
                if (last != null && now - last < CLICK_COOLDOWN_MS) return
                buy(p, shop)
            }
            Action.LEFT_CLICK_BLOCK -> p.sendMessage(
                "$PREFIX${ChatColor.WHITE}${shop.ownerName}'s shop${ChatColor.GRAY} sells ${ChatColor.AQUA}${shop.quantity}× " +
                    "${ChatColor.WHITE}${shop.displayName}${ChatColor.GRAY} for ${dia(shop.price)}. Right-click to buy!"
            )
            else -> {}
        }
    }

    /**
     * Buying runs in three steps so items and money stay in sync while the payment is on the database thread:
     * 1. Main thread: check stock and space, then reserve the items by taking them out of the chest,
     *    so nobody else can buy or remove them meanwhile.
     * 2. Database thread: the payment.
     * 3. Main thread: deliver the items, or put them back if the payment didn't go through. If the buyer
     *    left in between, the items go back and the payment is refunded.
     */
    private fun buy(buyer: Player, shop: Shop) {
        val chest = shops.inventory(shop)
        if (chest == null) {
            buyer.sendMessage("$PREFIX${ChatColor.RED}This shop is broken. Ask the owner to recreate it.")
            return
        }
        if (chest.countItems(shop.item) < shop.quantity) {
            shops.refresh(shop)
            buyer.sendMessage("${PREFIX}This shop is sold out, sorry!")
            buyer.playSound(buyer.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f)
            return
        }
        if (buyer.inventory.roomFor(shop.item) < shop.quantity) {
            buyer.sendMessage("${PREFIX}You don't have enough inventory space.")
            return
        }

        chest.takeItems(shop.item, shop.quantity)
        shops.refresh(shop)

        // The sale as the buyer saw it, in case the shop changes before the payment is done.
        val buyerId = buyer.uniqueId
        val owner = shop.owner
        val price = shop.price
        val quantity = shop.quantity
        val itemType = shop.item.type.name
        val itemName = shop.displayName
        worker.submit({
            bank.transfer(buyerId, owner, price, Bank.TxType.SHOP_PURCHASE, itemType, itemName, quantity, shop.id)
        }) { result ->
            val paid = result.getOrElse { e ->
                plugin.logger.log(Level.SEVERE, "Shop purchase failed", e)
                shops.returnItems(shop, quantity)
                buyer.sendMessage("$PREFIX${ChatColor.RED}Something went wrong, nothing was charged.")
                return@submit
            }
            when {
                !paid -> {
                    shops.returnItems(shop, quantity)
                    buyer.sendMessage("${PREFIX}You need ${dia(price)} in the bank. Use /deposit first.")
                }
                !buyer.isOnline -> {
                    // Paid, but gone before delivery: a logged-out player's inventory can't be changed.
                    shops.returnItems(shop, quantity)
                    refund(buyer, owner, price, itemType, itemName, quantity, shop.id)
                }
                else -> deliver(buyer, shop, owner, price, quantity, itemName)
            }
        }
    }

    private fun deliver(buyer: Player, shop: Shop, owner: UUID, price: Long, quantity: Int, itemName: String) {
        buyer.giveOrDrop(shop.item, quantity)
        val fx = shops.center(shop).add(0.0, 0.9, 0.0)
        fx.world!!.spawnParticle(Particle.HAPPY_VILLAGER, fx, 12, 0.35, 0.3, 0.35)
        buyer.playSound(fx, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f)
        buyer.playSound(fx, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f)
        val what = "${ChatColor.AQUA}$quantity× ${ChatColor.WHITE}$itemName${ChatColor.GRAY}"
        buyer.sendMessage("${PREFIX}Bought $what for ${dia(price)}. Thank you! ♥")
        Bukkit.getPlayer(owner)?.sendMessage(
            "$PREFIX${ChatColor.WHITE}${buyer.name}${ChatColor.GRAY} bought $what for ${dia(price)}."
        )
    }

    private fun refund(
        buyer: Player, owner: UUID, price: Long, itemType: String, itemName: String, quantity: Int, shopId: Long,
    ) {
        worker.submit({
            bank.transfer(owner, buyer.uniqueId, price, Bank.TxType.REFUND, itemType, itemName, quantity, shopId)
        }) { result ->
            if (result.getOrDefault(false)) {
                plugin.logger.info("${buyer.name} left before their purchase from shop #$shopId was delivered; refunded ${price}◆")
            } else {
                plugin.logger.log(
                    Level.SEVERE,
                    "${buyer.name} (${buyer.uniqueId}) paid ${price}◆ to $owner at shop #$shopId but left before delivery, " +
                        "and the refund failed. The items are back in the shop; refund the payment by hand.",
                    result.exceptionOrNull(),
                )
            }
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        shops.at(BlockKey.of(e.inventory.location))?.let(shops::refresh)
    }

    // --- protection ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) {
        val shop = shops.at(e.block) ?: return
        val p = e.player
        if (p.uniqueId != shop.owner && !p.hasPermission("diamondecon.admin")) {
            e.isCancelled = true
            p.sendMessage("${PREFIX}This is ${shop.ownerName}'s shop, hands off!")
            return
        }
        shops.remove(shop)
        p.sendMessage("${PREFIX}Shop closed.")
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(e: BlockPlaceEvent) {
        if (e.blockPlaced.blockData is Chest && shops.touchesShopChest(e.blockPlaced)) {
            e.isCancelled = true
            e.player.sendMessage("${PREFIX}You can't place a chest next to a shop.")
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(e: EntityExplodeEvent) {
        e.blockList().removeIf(::isShop)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(e: BlockExplodeEvent) {
        e.blockList().removeIf(::isShop)
    }

    /** Stops hoppers and hopper minecarts from draining shops. Filling a shop is fine. */
    @EventHandler(ignoreCancelled = true)
    fun onHopper(e: InventoryMoveItemEvent) {
        if (shops.at(BlockKey.of(e.source.location)) != null) e.isCancelled = true
    }

    @EventHandler
    fun onChunkLoad(e: ChunkLoadEvent) = shops.onChunkLoad(e.chunk)

    @EventHandler
    fun onChunkUnload(e: ChunkUnloadEvent) = shops.onChunkUnload(e.chunk)

    private fun isShop(b: Block) = shops.at(b) != null

    private companion object {
        const val CLICK_COOLDOWN_MS = 250L
    }
}
