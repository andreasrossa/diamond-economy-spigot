package dev.diamondecon

import dev.diamondecon.bank.Bank
import dev.diamondecon.command.EconomyCommands
import dev.diamondecon.command.ShopCommand
import dev.diamondecon.db.DbWorker
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
    private var worker: DbWorker? = null
    private var shops: ShopManager? = null

    override fun onEnable() {
        saveDefaultConfig()
        val storage: Storage
        val shops: ShopManager
        try {
            storage = Storage(config.getConfigurationSection("database") ?: config.createSection("database"), logger)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Could not connect to or set up the database, disabling", e)
            server.pluginManager.disablePlugin(this)
            return
        }
        this.storage = storage
        val worker = DbWorker(logger)
        this.worker = worker
        try {
            // Loaded synchronously: nothing should run before the shops are known.
            shops = ShopManager(this, storage.db, worker).also { it.load() }
        } catch (e: SQLException) {
            logger.log(Level.SEVERE, "Could not load shops, disabling", e)
            server.pluginManager.disablePlugin(this)
            return
        }
        this.shops = shops
        val bank = Bank(storage.db)

        val economy = EconomyCommands(this, bank, worker)
        for (name in listOf("balance", "deposit", "withdraw", "pay", "history", "stats", "baltop")) {
            register(name, economy, economy)
        }
        val shopCommand = ShopCommand(this, shops, bank, worker)
        register("shop", shopCommand, shopCommand)

        server.pluginManager.registerEvents(economy, this)
        server.pluginManager.registerEvents(ShopListener(this, shops, bank, worker), this)
        shops.start()
        worker.start(this)

        // Accounts for players already online after a /reload.
        for (p in server.onlinePlayers) {
            worker.write("create account for ${p.name}") { bank.ensureAccount(p.uniqueId, p.name) }
        }
    }

    override fun onDisable() {
        // Order matters: finish queued database work and its item steps while the shops and the
        // database are still there, then remove the decorations, then close the pool.
        worker?.stop()
        shops?.stop()
        storage?.close()
        worker = null
        storage = null
        shops = null
    }

    private fun register(name: String, executor: CommandExecutor, completer: TabCompleter) {
        val command = getCommand(name) ?: error("Command /$name missing from plugin.yml")
        command.setExecutor(executor)
        command.tabCompleter = completer
    }
}
