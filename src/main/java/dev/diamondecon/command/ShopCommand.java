package dev.diamondecon.command;

import dev.diamondecon.shop.Shop;
import dev.diamondecon.shop.ShopManager;
import dev.diamondecon.util.Fmt;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

/** /shop create|remove|price|list */
public final class ShopCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_QUANTITY = 27 * 64;

    private final JavaPlugin plugin;
    private final ShopManager shops;

    public ShopCommand(JavaPlugin plugin, ShopManager shops) {
        this.plugin = plugin;
        this.shops = shops;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        try {
            switch (args.length == 0 ? "help" : args[0].toLowerCase()) {
                case "create" -> create(p, args);
                case "remove" -> remove(p);
                case "price" -> price(p, args);
                case "list" -> list(p);
                default -> help(p);
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Command /shop failed", ex);
            p.sendMessage(Fmt.PREFIX + ChatColor.RED + "Something went wrong, nothing was changed.");
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(ChatColor.AQUA + "◆ " + ChatColor.WHITE + "Chest shops");
        p.sendMessage(ChatColor.GRAY + "  Hold the item you want to sell and look at a chest or barrel:");
        p.sendMessage(ChatColor.WHITE + "  /shop create <amount> <price>" + ChatColor.GRAY + " sell <amount> items for <price> diamonds");
        p.sendMessage(ChatColor.WHITE + "  /shop price <price>" + ChatColor.GRAY + " change the price of the shop you look at");
        p.sendMessage(ChatColor.WHITE + "  /shop remove" + ChatColor.GRAY + " close the shop you look at");
        p.sendMessage(ChatColor.WHITE + "  /shop list" + ChatColor.GRAY + " list your shops");
        p.sendMessage(ChatColor.GRAY + "  Customers right-click to buy. Money goes straight to your bank.");
    }

    private void create(Player p, String[] args) throws SQLException {
        if (args.length < 3) {
            p.sendMessage(Fmt.PREFIX + "Usage: /shop create <amount> <price>  e.g. /shop create 64 1");
            return;
        }
        Block block = p.getTargetBlockExact(5);
        if (block == null || !ShopManager.SHOP_BLOCKS.contains(block.getType())) {
            p.sendMessage(Fmt.PREFIX + "Look at a chest or barrel to turn it into a shop.");
            return;
        }
        if (block.getBlockData() instanceof Chest chest && chest.getType() != Chest.Type.SINGLE) {
            p.sendMessage(Fmt.PREFIX + "Shops only work on single chests.");
            return;
        }
        if (shops.at(block) != null) {
            p.sendMessage(Fmt.PREFIX + "That's already a shop.");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            p.sendMessage(Fmt.PREFIX + "Hold the item you want to sell.");
            return;
        }
        Long quantity = EconomyCommands.parseAmount(p, args[1]);
        if (quantity == null) return;
        if (quantity > MAX_QUANTITY) {
            p.sendMessage(Fmt.PREFIX + "You can sell at most " + MAX_QUANTITY + " items at once.");
            return;
        }
        Long price = EconomyCommands.parseAmount(p, args[2]);
        if (price == null) return;

        Shop shop = shops.create(p.getUniqueId(), p.getName(), block, hand, quantity.intValue(), price);
        p.sendMessage(Fmt.PREFIX + "Shop open! Selling " + ChatColor.AQUA + shop.quantity() + "× " + ChatColor.WHITE
                + Fmt.itemName(shop.item()) + ChatColor.GRAY + " for " + Fmt.dia(shop.price())
                + ". Fill the chest with stock.");
    }

    private void remove(Player p) throws SQLException {
        Shop shop = lookedAt(p);
        if (shop == null) return;
        shops.remove(shop);
        p.sendMessage(Fmt.PREFIX + "Shop closed. The chest and its items are untouched.");
    }

    private void price(Player p, String[] args) throws SQLException {
        if (args.length < 2) {
            p.sendMessage(Fmt.PREFIX + "Usage: /shop price <price>");
            return;
        }
        Shop shop = lookedAt(p);
        if (shop == null) return;
        Long price = EconomyCommands.parseAmount(p, args[1]);
        if (price == null) return;
        shops.setPrice(shop, price);
        p.sendMessage(Fmt.PREFIX + "Price set to " + Fmt.dia(price) + ".");
    }

    private void list(Player p) {
        List<Shop> mine = shops.ownedBy(p.getUniqueId());
        if (mine.isEmpty()) {
            p.sendMessage(Fmt.PREFIX + "You don't have any shops yet. Try /shop help");
            return;
        }
        p.sendMessage(ChatColor.AQUA + "◆ " + ChatColor.WHITE + "Your shops");
        for (Shop s : mine) {
            p.sendMessage(ChatColor.GRAY + "  " + s.quantity() + "× " + ChatColor.WHITE + Fmt.itemName(s.item())
                    + ChatColor.GRAY + " for " + Fmt.dia(s.price()) + " · stock " + s.stock()
                    + ChatColor.DARK_GRAY + " @ " + s.key());
        }
    }

    /** The shop the player is looking at, if they may manage it. */
    private Shop lookedAt(Player p) {
        Block block = p.getTargetBlockExact(5);
        Shop shop = block == null ? null : shops.at(block);
        if (shop == null) {
            p.sendMessage(Fmt.PREFIX + "Look at one of your shops.");
            return null;
        }
        if (!shop.owner().equals(p.getUniqueId()) && !p.hasPermission("diamondecon.admin")) {
            p.sendMessage(Fmt.PREFIX + "That's not your shop.");
            return null;
        }
        return shop;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("create", "remove", "price", "list", "help").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
