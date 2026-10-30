package dev.diamondecon.shop;

import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** A chest/barrel that sells {@code quantity} of {@code item} for {@code price} diamonds. */
public final class Shop {

    final long id;
    final UUID owner;
    final String ownerName;
    final BlockKey key;
    final ItemStack item;
    final int quantity;
    long price;
    int stock;

    // Decorations are non-persistent and respawned whenever the chunk loads.
    ItemDisplay itemDisplay;
    TextDisplay label;
    float angle;

    Shop(long id, UUID owner, String ownerName, BlockKey key, ItemStack item, int quantity, long price, int stock) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.key = key;
        this.item = item;
        this.quantity = quantity;
        this.price = price;
        this.stock = stock;
    }

    public long id() { return id; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public BlockKey key() { return key; }
    public ItemStack item() { return item.clone(); }
    public int quantity() { return quantity; }
    public long price() { return price; }
    public int stock() { return stock; }
}
