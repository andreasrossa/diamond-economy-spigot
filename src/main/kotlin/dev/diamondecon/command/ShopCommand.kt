package dev.diamondecon.command

import dev.diamondecon.bank.Bank
import dev.diamondecon.command.EconomyCommands.Companion.parseAmount
import dev.diamondecon.shop.Shop
import dev.diamondecon.shop.ShopManager
import dev.diamondecon.util.PREFIX
import dev.diamondecon.util.dia
import dev.diamondecon.util.prettyName
import org.bukkit.ChatColor
import org.bukkit.block.data.type.Chest
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.sql.SQLException
import java.util.logging.Level

/** /shop create|remove|price|list, plus admin-only reload|setowner */
class ShopCommand(
    private val plugin: JavaPlugin,
    private val shops: ShopManager,
    private val bank: Bank,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }
        try {
            when (args.firstOrNull()?.lowercase()) {
                "create" -> create(sender, args)
                "remove" -> remove(sender)
                "price" -> price(sender, args)
                "list" -> list(sender)
                "reload" -> if (requireAdmin(sender)) reload(sender)
                "setowner" -> if (requireAdmin(sender)) setOwner(sender, args)
                else -> help(sender)
            }
        } catch (ex: SQLException) {
            plugin.logger.log(Level.SEVERE, "Command /shop failed", ex)
            sender.sendMessage("$PREFIX${ChatColor.RED}Something went wrong, nothing was changed.")
        }
        return true
    }

    private fun help(p: Player) {
        val w = ChatColor.WHITE
        val g = ChatColor.GRAY
        p.sendMessage("${ChatColor.AQUA}◆ ${w}Chest shops")
        p.sendMessage("$g  Hold the item you want to sell and look at a chest or barrel:")
        p.sendMessage("$w  /shop create <amount> <price>$g sell <amount> items for <price> diamonds")
        p.sendMessage("$w  /shop price <price>$g change the price of the shop you look at")
        p.sendMessage("$w  /shop remove$g close the shop you look at")
        p.sendMessage("$w  /shop list$g list your shops")
        p.sendMessage("$g  Customers right-click to buy. Money goes straight to your bank.")
        if (p.hasPermission(ADMIN)) {
            p.sendMessage("$w  /shop setowner <player>$g give the shop you look at to someone else")
            p.sendMessage("$w  /shop reload$g reload all shops from the database")
        }
    }

    private fun create(p: Player, args: Array<String>) {
        if (args.size < 3) {
            p.sendMessage("${PREFIX}Usage: /shop create <amount> <price>  e.g. /shop create 64 1")
            return
        }
        val block = p.getTargetBlockExact(5)
        if (block == null || block.type !in ShopManager.SHOP_BLOCKS) {
            p.sendMessage("${PREFIX}Look at a chest or barrel to turn it into a shop.")
            return
        }
        val data = block.blockData
        if (data is Chest && data.type != Chest.Type.SINGLE) {
            p.sendMessage("${PREFIX}Shops only work on single chests.")
            return
        }
        if (shops.at(block) != null) {
            p.sendMessage("${PREFIX}That's already a shop.")
            return
        }
        val hand = p.inventory.itemInMainHand
        if (hand.type.isAir) {
            p.sendMessage("${PREFIX}Hold the item you want to sell.")
            return
        }
        val quantity = parseAmount(p, args[1]) ?: return
        if (quantity > MAX_QUANTITY) {
            p.sendMessage("${PREFIX}You can sell at most $MAX_QUANTITY items at once.")
            return
        }
        val price = parseAmount(p, args[2]) ?: return

        val shop = shops.create(p.uniqueId, p.name, block, hand, quantity.toInt(), price)
        p.sendMessage(
            "${PREFIX}Shop open! Selling ${ChatColor.AQUA}${shop.quantity}× ${ChatColor.WHITE}${shop.item.prettyName}" +
                "${ChatColor.GRAY} for ${dia(shop.price)}. Fill the chest with stock."
        )
    }

    private fun remove(p: Player) {
        val shop = lookedAt(p) ?: return
        shops.remove(shop)
        p.sendMessage("${PREFIX}Shop closed. The chest and its items are untouched.")
    }

    private fun price(p: Player, args: Array<String>) {
        if (args.size < 2) {
            p.sendMessage("${PREFIX}Usage: /shop price <price>")
            return
        }
        val shop = lookedAt(p) ?: return
        val price = parseAmount(p, args[1]) ?: return
        shops.setPrice(shop, price)
        p.sendMessage("${PREFIX}Price set to ${dia(price)}.")
    }

    private fun list(p: Player) {
        val mine = shops.ownedBy(p.uniqueId)
        if (mine.isEmpty()) {
            p.sendMessage("${PREFIX}You don't have any shops yet. Try /shop help")
            return
        }
        p.sendMessage("${ChatColor.AQUA}◆ ${ChatColor.WHITE}Your shops")
        for (s in mine) {
            p.sendMessage(
                "${ChatColor.GRAY}  ${s.quantity}× ${ChatColor.WHITE}${s.item.prettyName}${ChatColor.GRAY} for ${dia(s.price)}" +
                    " · stock ${s.stock}${ChatColor.DARK_GRAY} @ ${s.key}"
            )
        }
    }

    private fun reload(p: Player) {
        val count = shops.reload()
        p.sendMessage("${PREFIX}Reloaded $count shops from the database.")
    }

    private fun setOwner(p: Player, args: Array<String>) {
        if (args.size < 2) {
            p.sendMessage("${PREFIX}Usage: /shop setowner <player>")
            return
        }
        val shop = lookedAt(p) ?: return
        val target = bank.findByName(args[1])
        if (target == null) {
            p.sendMessage("${PREFIX}No player called ${args[1]} has joined this server.")
            return
        }
        if (target.uuid == shop.owner) {
            p.sendMessage("${PREFIX}That shop already belongs to ${target.name}.")
            return
        }
        shops.setOwner(shop, target.uuid, target.name)
        p.sendMessage("${PREFIX}The shop now belongs to ${ChatColor.WHITE}${target.name}${ChatColor.GRAY}.")
    }

    private fun requireAdmin(p: Player): Boolean {
        if (p.hasPermission(ADMIN)) return true
        p.sendMessage("${PREFIX}Only admins can do that.")
        return false
    }

    /** The shop the player is looking at, if they may manage it. */
    private fun lookedAt(p: Player): Shop? {
        val shop = p.getTargetBlockExact(5)?.let(shops::at)
        if (shop == null) {
            p.sendMessage("${PREFIX}Look at one of your shops.")
            return null
        }
        if (shop.owner != p.uniqueId && !p.hasPermission(ADMIN)) {
            p.sendMessage("${PREFIX}That's not your shop.")
            return null
        }
        return shop
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String>? {
        val admin = sender.hasPermission(ADMIN)
        return when {
            args.size == 1 -> (if (admin) SUBCOMMANDS + ADMIN_SUBCOMMANDS else SUBCOMMANDS)
                .filter { it.startsWith(args[0].lowercase()) }
            args.size == 2 && admin && args[0].equals("setowner", ignoreCase = true) -> null // online player names
            else -> emptyList()
        }
    }

    private companion object {
        const val MAX_QUANTITY = 27 * 64
        const val ADMIN = "diamondecon.admin"
        val SUBCOMMANDS = listOf("create", "remove", "price", "list", "help")
        val ADMIN_SUBCOMMANDS = listOf("setowner", "reload")
    }
}
