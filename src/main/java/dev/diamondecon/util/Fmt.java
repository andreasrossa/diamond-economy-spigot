package dev.diamondecon.util;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

public final class Fmt {

    public static final String PREFIX = ChatColor.AQUA + "◆ " + ChatColor.GRAY;

    private Fmt() {}

    public static String dia(long amount) {
        return ChatColor.AQUA + "" + amount + "◆" + ChatColor.GRAY;
    }

    public static String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String name = meta != null && meta.hasDisplayName()
                ? meta.getDisplayName()
                : materialName(item.getType().name());
        return item.getEnchantments().isEmpty() ? name : name + ChatColor.LIGHT_PURPLE + " ✦";
    }

    /** "DIAMOND_SWORD" -> "Diamond Sword" */
    public static String materialName(String material) {
        StringBuilder out = new StringBuilder();
        for (String word : material.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
