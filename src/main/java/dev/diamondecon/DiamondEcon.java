package dev.diamondecon;

import dev.diamondecon.bank.Bank;
import dev.diamondecon.command.EconomyCommands;
import dev.diamondecon.command.ShopCommand;
import dev.diamondecon.shop.ShopListener;
import dev.diamondecon.shop.ShopManager;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.logging.Level;

public final class DiamondEcon extends JavaPlugin {

    private Database database;
    private ShopManager shops;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        try {
            database = new Database(new File(getDataFolder(), "economy.db"));
            shops = new ShopManager(this, database.connection());
            shops.load();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Could not open the database, disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Bank bank = new Bank(database.connection());

        EconomyCommands economy = new EconomyCommands(this, bank);
        for (String name : new String[]{"balance", "deposit", "withdraw", "pay", "history", "stats", "baltop"}) {
            register(name, economy, economy);
        }
        ShopCommand shopCommand = new ShopCommand(this, shops);
        register("shop", shopCommand, shopCommand);

        getServer().getPluginManager().registerEvents(economy, this);
        getServer().getPluginManager().registerEvents(new ShopListener(this, shops, bank), this);
        shops.start();

        // Accounts for players already online after a /reload.
        for (Player p : getServer().getOnlinePlayers()) {
            try {
                bank.ensureAccount(p.getUniqueId(), p.getName());
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Could not create account for " + p.getName(), e);
            }
        }
    }

    @Override
    public void onDisable() {
        if (shops != null) shops.stop();
        if (database != null) {
            try {
                database.close();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Could not close the database", e);
            }
        }
    }

    private void register(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand command = getCommand(name);
        command.setExecutor(executor);
        command.setTabCompleter(completer);
    }
}
