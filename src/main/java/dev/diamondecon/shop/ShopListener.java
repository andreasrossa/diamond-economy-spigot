package dev.diamondecon.shop;

import dev.diamondecon.bank.Bank;
import dev.diamondecon.util.Fmt;
import dev.diamondecon.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Buying from shops, plus everything that keeps shop chests safe from non-owners. */
public final class ShopListener implements Listener {

    private static final long CLICK_COOLDOWN_MS = 250;

    private final JavaPlugin plugin;
    private final ShopManager shops;
    private final Bank bank;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public ShopListener(JavaPlugin plugin, ShopManager shops, Bank bank) {
        this.plugin = plugin;
        this.shops = shops;
        this.bank = bank;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Shop shop = shops.at(e.getClickedBlock());
        if (shop == null) return;
        Player p = e.getPlayer();
        if (p.getUniqueId().equals(shop.owner)) return; // owners open their chest to restock

        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
            long now = System.currentTimeMillis();
            Long last = lastClick.put(p.getUniqueId(), now);
            if (last != null && now - last < CLICK_COOLDOWN_MS) return;
            buy(p, shop);
        } else if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            p.sendMessage(Fmt.PREFIX + ChatColor.WHITE + shop.ownerName + "'s shop" + ChatColor.GRAY + " sells "
                    + ChatColor.AQUA + shop.quantity + "× " + ChatColor.WHITE + Fmt.itemName(shop.item)
                    + ChatColor.GRAY + " for " + Fmt.dia(shop.price) + ". Right-click to buy!");
        }
    }

    private void buy(Player buyer, Shop shop) {
        Inventory chest = shops.inventory(shop);
        if (chest == null) {
            buyer.sendMessage(Fmt.PREFIX + ChatColor.RED + "This shop is broken. Ask the owner to recreate it.");
            return;
        }
        if (Items.count(chest, shop.item) < shop.quantity) {
            shops.refresh(shop);
            buyer.sendMessage(Fmt.PREFIX + "This shop is sold out, sorry!");
            buyer.playSound(buyer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
            return;
        }
        if (Items.room(buyer.getInventory(), shop.item) < shop.quantity) {
            buyer.sendMessage(Fmt.PREFIX + "You don't have enough inventory space.");
            return;
        }
        boolean paid;
        try {
            paid = bank.transfer(buyer.getUniqueId(), shop.owner, shop.price, Bank.TxType.SHOP_PURCHASE,
                    shop.item.getType().name(), shop.quantity, shop.id);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Shop purchase failed", ex);
            buyer.sendMessage(Fmt.PREFIX + ChatColor.RED + "Something went wrong, nothing was charged.");
            return;
        }
        if (!paid) {
            buyer.sendMessage(Fmt.PREFIX + "You need " + Fmt.dia(shop.price) + " in the bank. Use /deposit first.");
            return;
        }

        // Payment committed; everything below is in-memory on the main thread and cannot fail halfway.
        Items.remove(chest, shop.item, shop.quantity);
        int leftover = Items.give(buyer.getInventory(), shop.item, shop.quantity);
        if (leftover > 0) {
            ItemStack drop = shop.item.clone();
            drop.setAmount(leftover);
            buyer.getWorld().dropItemNaturally(buyer.getLocation(), drop);
        }
        shops.refresh(shop);

        Location fx = shops.center(shop).add(0, 0.9, 0);
        fx.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, fx, 12, 0.35, 0.3, 0.35);
        buyer.playSound(fx, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
        buyer.playSound(fx, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        buyer.sendMessage(Fmt.PREFIX + "Bought " + ChatColor.AQUA + shop.quantity + "× " + ChatColor.WHITE
                + Fmt.itemName(shop.item) + ChatColor.GRAY + " for " + Fmt.dia(shop.price) + ". Thank you! ♥");

        Player owner = Bukkit.getPlayer(shop.owner);
        if (owner != null) {
            owner.sendMessage(Fmt.PREFIX + ChatColor.WHITE + buyer.getName() + ChatColor.GRAY + " bought "
                    + ChatColor.AQUA + shop.quantity + "× " + ChatColor.WHITE + Fmt.itemName(shop.item)
                    + ChatColor.GRAY + " for " + Fmt.dia(shop.price) + ".");
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Shop shop = shops.at(BlockKey.of(e.getInventory().getLocation()));
        if (shop != null) shops.refresh(shop);
    }

    // --- protection ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Shop shop = shops.at(e.getBlock());
        if (shop == null) return;
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(shop.owner) && !p.hasPermission("diamondecon.admin")) {
            e.setCancelled(true);
            p.sendMessage(Fmt.PREFIX + "This is " + shop.ownerName + "'s shop, hands off!");
            return;
        }
        try {
            shops.remove(shop);
            p.sendMessage(Fmt.PREFIX + "Shop closed.");
        } catch (SQLException ex) {
            e.setCancelled(true);
            plugin.getLogger().log(Level.SEVERE, "Could not remove shop #" + shop.id, ex);
            p.sendMessage(Fmt.PREFIX + ChatColor.RED + "Could not remove the shop, try again.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getBlockData() instanceof Chest && shops.touchesShopChest(e.getBlockPlaced())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Fmt.PREFIX + "You can't place a chest next to a shop.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isShop);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isShop);
    }

    /** Stops hoppers and hopper minecarts from draining shops. Filling a shop is fine. */
    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (shops.at(BlockKey.of(e.getSource().getLocation())) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        shops.onChunkLoad(e.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        shops.onChunkUnload(e.getChunk());
    }

    private boolean isShop(Block b) {
        return shops.at(b) != null;
    }
}
