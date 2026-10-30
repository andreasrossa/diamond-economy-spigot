package dev.diamondecon

import dev.diamondecon.bank.Bank
import dev.diamondecon.command.EconomyCommands
import dev.diamondecon.command.ShopCommand
import dev.diamondecon.shop.ShopListener
import dev.diamondecon.shop.ShopManager
import org.bukkit.command.CommandExecutor
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.SQLException
import java.util.logging.Level

class DiamondEcon : JavaPlugin() {

    private var database: Database? = null
    private var shops: ShopManager? = null

    override fun onEnable() {
        dataFolder.mkdirs()
        val db: Database
        val shops: ShopManager
        try {
            db = Database(File(dataFolder, "economy.db"))
            shops = ShopManager(this, db.connection).also { it.load() }
        } catch (e: SQLException) {
            logger.log(Level.SEVERE, "Could not open the database, disabling", e)
            server.pluginManager.disablePlugin(this)
            return
        }
        database = db
        this.shops = shops
        val bank = Bank(db.connection)

        val economy = EconomyCommands(this, bank)
        for (name in listOf("balance", "deposit", "withdraw", "pay", "history", "stats", "baltop")) {
            register(name, economy, economy)
        }
        val shopCommand = ShopCommand(this, shops)
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
        try {
            database?.close()
        } catch (e: SQLException) {
            logger.log(Level.WARNING, "Could not close the database", e)
        }
    }

    private fun register(name: String, executor: CommandExecutor, completer: TabCompleter) {
        val command = getCommand(name) ?: error("Command /$name missing from plugin.yml")
        command.setExecutor(executor)
        command.tabCompleter = completer
    }
}
