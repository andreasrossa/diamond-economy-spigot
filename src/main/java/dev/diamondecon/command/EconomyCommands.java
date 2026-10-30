package dev.diamondecon.command;

import dev.diamondecon.bank.Bank;
import dev.diamondecon.util.Fmt;
import dev.diamondecon.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/** /balance, /deposit, /withdraw, /pay, /history, /stats, /baltop */
public final class EconomyCommands implements CommandExecutor, TabCompleter, Listener {

    private static final int HISTORY_PAGE_SIZE = 8;
    private static final ItemStack DIAMOND = new ItemStack(Material.DIAMOND);
    private static final ItemStack DIAMOND_BLOCK = new ItemStack(Material.DIAMOND_BLOCK);

    private final JavaPlugin plugin;
    private final Bank bank;

    public EconomyCommands(JavaPlugin plugin, Bank bank) {
        this.plugin = plugin;
        this.bank = bank;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        try {
            bank.ensureAccount(e.getPlayer().getUniqueId(), e.getPlayer().getName());
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not create account for " + e.getPlayer().getName(), ex);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            switch (command.getName()) {
                case "balance" -> balance(sender, args);
                case "baltop" -> baltop(sender);
                default -> {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage("Only players can use this command.");
                        return true;
                    }
                    switch (command.getName()) {
                        case "deposit" -> deposit(p, args);
                        case "withdraw" -> withdraw(p, args);
                        case "pay" -> pay(p, args);
                        case "history" -> history(p, args);
                        case "stats" -> stats(p);
                    }
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Command /" + command.getName() + " failed", ex);
            sender.sendMessage(Fmt.PREFIX + ChatColor.RED + "Something went wrong, nothing was changed.");
        }
        return true;
    }

    private void balance(CommandSender sender, String[] args) throws SQLException {
        if (args.length > 0) {
            if (!sender.hasPermission("diamondecon.admin")) {
                sender.sendMessage(Fmt.PREFIX + "You can only check your own balance.");
                return;
            }
            Optional<Bank.Account> acc = bank.findByName(args[0]);
            sender.sendMessage(acc.map(a -> Fmt.PREFIX + ChatColor.WHITE + a.name() + ChatColor.GRAY + " has " + Fmt.dia(a.balance()))
                    .orElse(Fmt.PREFIX + "No player called " + args[0] + "."));
            return;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Usage: /balance <player>");
            return;
        }
        sender.sendMessage(Fmt.PREFIX + "Balance: " + Fmt.dia(bank.balance(p.getUniqueId())));
    }

    private void deposit(Player p, String[] args) throws SQLException {
        PlayerInventory inv = p.getInventory();
        int diamonds = Items.count(inv, DIAMOND);
        boolean all = args.length == 0 || args[0].equalsIgnoreCase("all");
        int blocks = all ? Items.count(inv, DIAMOND_BLOCK) : 0;
        long amount;
        if (all) {
            amount = diamonds + blocks * 9L;
        } else {
            Long parsed = parseAmount(p, args[0]);
            if (parsed == null) return;
            if (parsed > diamonds) {
                p.sendMessage(Fmt.PREFIX + "You only have " + Fmt.dia(diamonds) + " in your inventory.");
                return;
            }
            amount = parsed;
        }
        if (amount == 0) {
            p.sendMessage(Fmt.PREFIX + "You don't have any diamonds to deposit.");
            return;
        }
        bank.deposit(p.getUniqueId(), amount);
        // Committed; take the items (counted above on the same tick).
        if (all) {
            Items.remove(inv, DIAMOND, diamonds);
            Items.remove(inv, DIAMOND_BLOCK, blocks);
        } else {
            Items.remove(inv, DIAMOND, (int) amount);
        }
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        p.sendMessage(Fmt.PREFIX + "Deposited " + Fmt.dia(amount) + ". Balance: " + Fmt.dia(bank.balance(p.getUniqueId())));
    }

    private void withdraw(Player p, String[] args) throws SQLException {
        if (args.length == 0) {
            p.sendMessage(Fmt.PREFIX + "Usage: /withdraw <amount|all>");
            return;
        }
        long balance = bank.balance(p.getUniqueId());
        long requested;
        if (args[0].equalsIgnoreCase("all")) {
            requested = balance;
        } else {
            Long parsed = parseAmount(p, args[0]);
            if (parsed == null) return;
            requested = parsed;
        }
        if (requested == 0 || requested > balance) {
            p.sendMessage(Fmt.PREFIX + "You only have " + Fmt.dia(balance) + " in the bank.");
            return;
        }
        int room = Items.room(p.getInventory(), DIAMOND);
        int amount = (int) Math.min(requested, room);
        if (amount == 0) {
            p.sendMessage(Fmt.PREFIX + "Your inventory is full.");
            return;
        }
        if (!bank.withdraw(p.getUniqueId(), amount)) {
            p.sendMessage(Fmt.PREFIX + "You don't have enough diamonds in the bank.");
            return;
        }
        Items.give(p.getInventory(), DIAMOND, amount);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        String note = amount < requested ? " (only " + amount + " fit in your inventory)" : "";
        p.sendMessage(Fmt.PREFIX + "Withdrew " + Fmt.dia(amount) + note + ". Balance: "
                + Fmt.dia(bank.balance(p.getUniqueId())));
    }

    private void pay(Player p, String[] args) throws SQLException {
        if (args.length < 2) {
            p.sendMessage(Fmt.PREFIX + "Usage: /pay <player> <amount>");
            return;
        }
        Optional<Bank.Account> target = bank.findByName(args[0]);
        if (target.isEmpty()) {
            p.sendMessage(Fmt.PREFIX + "No player called " + args[0] + " has joined this server.");
            return;
        }
        UUID to = target.get().uuid();
        if (to.equals(p.getUniqueId())) {
            p.sendMessage(Fmt.PREFIX + "You can't pay yourself.");
            return;
        }
        Long amount = parseAmount(p, args[1]);
        if (amount == null) return;
        if (!bank.transfer(p.getUniqueId(), to, amount, Bank.TxType.PAY, null, 0, null)) {
            p.sendMessage(Fmt.PREFIX + "You don't have " + Fmt.dia(amount) + " in the bank.");
            return;
        }
        String name = target.get().name();
        p.sendMessage(Fmt.PREFIX + "Sent " + Fmt.dia(amount) + " to " + ChatColor.WHITE + name + ChatColor.GRAY + ".");
        Player online = Bukkit.getPlayer(to);
        if (online != null) {
            online.sendMessage(Fmt.PREFIX + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " sent you " + Fmt.dia(amount) + "!");
            online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
    }

    private void history(Player p, String[] args) throws SQLException {
        int page = 1;
        if (args.length > 0) {
            try {
                page = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException e) {
                p.sendMessage(Fmt.PREFIX + "Usage: /history [page]");
                return;
            }
        }
        UUID me = p.getUniqueId();
        List<Bank.Txn> txns = bank.history(me, HISTORY_PAGE_SIZE, (page - 1) * HISTORY_PAGE_SIZE);
        if (txns.isEmpty()) {
            p.sendMessage(Fmt.PREFIX + (page == 1 ? "No transactions yet." : "No more transactions."));
            return;
        }
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm");
        p.sendMessage(ChatColor.AQUA + "◆ " + ChatColor.WHITE + "History " + ChatColor.GRAY + "(page " + page + ")");
        for (Bank.Txn t : txns) {
            boolean incoming = me.equals(t.to());
            String amount = (incoming ? ChatColor.GREEN + "+" : ChatColor.RED + "-") + t.amount() + "◆";
            p.sendMessage(ChatColor.DARK_GRAY + time.format(new Date(t.createdAt())) + " " + amount + " "
                    + ChatColor.GRAY + describe(t, incoming));
        }
        p.sendMessage(ChatColor.DARK_GRAY + "/history " + (page + 1) + " for more");
    }

    private static String describe(Bank.Txn t, boolean incoming) {
        String other = incoming ? t.fromName() : t.toName();
        return switch (t.type()) {
            case DEPOSIT -> "deposited";
            case WITHDRAW -> "withdrew";
            case PAY -> incoming ? "from " + other : "to " + other;
            case SHOP_PURCHASE -> (incoming ? "sold " : "bought ") + t.itemAmount() + "× "
                    + Fmt.materialName(t.item()) + (incoming ? " to " : " from ") + other;
        };
    }

    private void stats(Player p) throws SQLException {
        UUID me = p.getUniqueId();
        Bank.Stats s = bank.stats(me);
        p.sendMessage(ChatColor.AQUA + "◆ " + ChatColor.WHITE + "Your diamond stats");
        p.sendMessage(ChatColor.GRAY + "  Balance: " + Fmt.dia(bank.balance(me)));
        p.sendMessage(ChatColor.GRAY + "  Deposited " + Fmt.dia(s.deposited()) + ", withdrew " + Fmt.dia(s.withdrawn()));
        p.sendMessage(ChatColor.GRAY + "  Received " + Fmt.dia(s.received()) + ", sent " + Fmt.dia(s.sent()));
        p.sendMessage(ChatColor.GRAY + "  Shop sales " + Fmt.dia(s.shopIncome()) + ", shop spending " + Fmt.dia(s.shopSpent()));
        p.sendMessage(ChatColor.DARK_GRAY + "  " + s.count() + " transactions total");
    }

    private void baltop(CommandSender sender) throws SQLException {
        List<Bank.Account> top = bank.top(10);
        sender.sendMessage(ChatColor.AQUA + "◆ " + ChatColor.WHITE + "Richest players");
        for (int i = 0; i < top.size(); i++) {
            Bank.Account a = top.get(i);
            sender.sendMessage(ChatColor.GRAY + "  " + (i + 1) + ". " + ChatColor.WHITE + a.name() + " " + Fmt.dia(a.balance()));
        }
    }

    /** Positive whole number, or null after telling the player why not. */
    static Long parseAmount(CommandSender sender, String s) {
        try {
            long n = Long.parseLong(s);
            if (n > 0 && n <= 1_000_000_000L) return n;
        } catch (NumberFormatException ignored) {
        }
        sender.sendMessage(Fmt.PREFIX + "'" + s + "' is not a valid amount.");
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return switch (command.getName()) {
            case "deposit", "withdraw" -> args.length == 1 ? List.of("all") : List.of();
            case "pay" -> args.length == 1 ? null : List.of(); // null = online player names
            case "balance" -> args.length == 1 && sender.hasPermission("diamondecon.admin") ? null : List.of();
            default -> List.of();
        };
    }
}
