package dev.diamondecon.util;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Counting, removing and adding items by "similarity" (same type and meta, any amount). */
public final class Items {

    private Items() {}

    public static int count(Inventory inv, ItemStack proto) {
        int n = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s != null && s.isSimilar(proto)) n += s.getAmount();
        }
        return n;
    }

    /** How many more of {@code proto} fit into the inventory. */
    public static int room(Inventory inv, ItemStack proto) {
        int max = Math.min(proto.getMaxStackSize(), inv.getMaxStackSize());
        int n = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) n += max;
            else if (s.isSimilar(proto)) n += Math.max(0, max - s.getAmount());
        }
        return n;
    }

    /** Removes up to {@code amount}; callers should check {@link #count} first. */
    public static void remove(Inventory inv, ItemStack proto, int amount) {
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || !s.isSimilar(proto)) continue;
            int take = Math.min(amount, s.getAmount());
            amount -= take;
            if (take == s.getAmount()) contents[i] = null;
            else s.setAmount(s.getAmount() - take);
        }
        inv.setStorageContents(contents);
    }

    /** Adds {@code amount}; callers should check {@link #room} first. Returns how many did not fit. */
    public static int give(Inventory inv, ItemStack proto, int amount) {
        int leftover = 0;
        int max = proto.getMaxStackSize();
        while (amount > 0) {
            ItemStack stack = proto.clone();
            stack.setAmount(Math.min(max, amount));
            amount -= stack.getAmount();
            for (ItemStack rest : inv.addItem(stack).values()) leftover += rest.getAmount();
        }
        return leftover;
    }
}
