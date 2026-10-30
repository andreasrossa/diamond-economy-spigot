package dev.diamondecon

import dev.diamondecon.bank.Bank
import dev.diamondecon.command.EconomyCommands
import dev.diamondecon.command.ShopCommand
import dev.diamondecon.db.Storage
import dev.diamondecon.shop.ShopListener
import dev.diamondecon.shop.ShopManager
import org.bukkit.command.CommandExecutor
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import java.sql.SQLException
import java.util.logging.Level

class DiamondEcon : JavaPlugin() {

    private var storage: Storage? = null
    private var shops: ShopManager? = null

    override fun onEnable() {
        saveDefaultConfig()
        val storage: Storage
        val shops: ShopManager
        try {
            storage = Storage(config.getConfigurationSection("database") ?: config.createSection("database"))
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Could not connect to the database, disabling", e)
            server.pluginManager.disablePlugin(this)
            return
        }
        this.storage = storage
        try {
            shops = ShopManager(this, storage.db).also { it.load() }
        } catch (e: SQLException) {
            logger.log(Level.SEVERE, "Could not load shops, disabling", e)
            server.pluginManager.disablePlugin(this)
            return
        }
        this.shops = shops
        val bank = Bank(storage.db)

        val economy = EconomyCommands(this, bank)
        for (name in listOf("balance", "deposit", "withdraw", "pay", "history", "stats", "baltop")) {
            register(name, economy, economy)
        }
        val shopCommand = ShopCommand(this, shops, bank)
        register("shop", shopCommand, shopCommand)

        server.pluginManager.registerEvents(economy, this)
        server.pluginManager.registerEvents(ShopListener(this, shops, bank), this)
        shops.start()

        // Accounts for players already online after a /reload.
        for (p in server.onlinePlayers) {
            try {
                bank.ensureAccount(p.uniqueId, p.name)
            } catch (e: SQLException) {
                logger.log(Level.SEVERE, "Could not create account for ${p.name}", e)
            }
        }
    }

    override fun onDisable() {
        shops?.stop()
        storage?.close()
        storage = null
        shops = null
    }

    private fun register(name: String, executor: CommandExecutor, completer: TabCompleter) {
        val command = getCommand(name) ?: error("Command /$name missing from plugin.yml")
        command.setExecutor(executor)
        command.tabCompleter = completer
    }
}
