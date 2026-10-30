package dev.diamondecon.command

import dev.diamondecon.bank.Bank
import dev.diamondecon.util.PREFIX
import dev.diamondecon.util.countItems
import dev.diamondecon.util.dia
import dev.diamondecon.util.giveItems
import dev.diamondecon.util.materialName
import dev.diamondecon.util.roomFor
import dev.diamondecon.util.takeItems
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.sql.SQLException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.Level

/** /balance, /deposit, /withdraw, /pay, /history, /stats, /baltop */
class EconomyCommands(private val plugin: JavaPlugin, private val bank: Bank) : CommandExecutor, TabCompleter, Listener {

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        try {
            bank.ensureAccount(e.player.uniqueId, e.player.name)
        } catch (ex: SQLException) {
            plugin.logger.log(Level.SEVERE, "Could not create account for ${e.player.name}", ex)
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        try {
            when (command.name) {
                "balance" -> balance(sender, args)
                "baltop" -> baltop(sender)
                else -> {
                    if (sender !is Player) {
                        sender.sendMessage("Only players can use this command.")
                        return true
                    }
                    when (command.name) {
                        "deposit" -> deposit(sender, args)
                        "withdraw" -> withdraw(sender, args)
                        "pay" -> pay(sender, args)
                        "history" -> history(sender, args)
                        "stats" -> stats(sender)
                    }
                }
            }
        } catch (ex: SQLException) {
            plugin.logger.log(Level.SEVERE, "Command /${command.name} failed", ex)
            sender.sendMessage("$PREFIX${ChatColor.RED}Something went wrong, nothing was changed.")
        }
        return true
    }

    private fun balance(sender: CommandSender, args: Array<String>) {
        if (args.isNotEmpty()) {
            if (!sender.hasPermission("diamondecon.admin")) {
                sender.sendMessage("${PREFIX}You can only check your own balance.")
                return
            }
            val acc = bank.findByName(args[0])
            sender.sendMessage(
                if (acc != null) "$PREFIX${ChatColor.WHITE}${acc.name}${ChatColor.GRAY} has ${dia(acc.balance)}"
                else "${PREFIX}No player called ${args[0]}."
            )
            return
        }
        if (sender !is Player) {
            sender.sendMessage("Usage: /balance <player>")
            return
        }
        sender.sendMessage("${PREFIX}Balance: ${dia(bank.balance(sender.uniqueId))}")
    }

    private fun deposit(p: Player, args: Array<String>) {
        val inv = p.inventory
        val diamonds = inv.countItems(DIAMOND)
        val all = args.isEmpty() || args[0].equals("all", ignoreCase = true)
        val blocks = if (all) inv.countItems(DIAMOND_BLOCK) else 0
        val amount = if (all) {
            diamonds + blocks * 9L
        } else {
            val parsed = parseAmount(p, args[0]) ?: return
            if (parsed > diamonds) {
                p.sendMessage("${PREFIX}You only have ${dia(diamonds)} in your inventory.")
                return
            }
            parsed
        }
        if (amount == 0L) {
            p.sendMessage("${PREFIX}You don't have any diamonds to deposit.")
            return
        }
        bank.deposit(p.uniqueId, amount)
        // Committed; take the items (counted above on the same tick).
        if (all) {
            inv.takeItems(DIAMOND, diamonds)
            inv.takeItems(DIAMOND_BLOCK, blocks)
        } else {
            inv.takeItems(DIAMOND, amount.toInt())
        }
        p.playSound(p.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f)
        p.sendMessage("${PREFIX}Deposited ${dia(amount)}. Balance: ${dia(bank.balance(p.uniqueId))}")
    }

    private fun withdraw(p: Player, args: Array<String>) {
        if (args.isEmpty()) {
            p.sendMessage("${PREFIX}Usage: /withdraw <amount|all>")
            return
        }
        val balance = bank.balance(p.uniqueId)
        val requested = if (args[0].equals("all", ignoreCase = true)) balance else parseAmount(p, args[0]) ?: return
        if (requested == 0L || requested > balance) {
            p.sendMessage("${PREFIX}You only have ${dia(balance)} in the bank.")
            return
        }
        val amount = minOf(requested, p.inventory.roomFor(DIAMOND).toLong()).toInt()
        if (amount == 0) {
            p.sendMessage("${PREFIX}Your inventory is full.")
            return
        }
        if (!bank.withdraw(p.uniqueId, amount.toLong())) {
            p.sendMessage("${PREFIX}You don't have enough diamonds in the bank.")
            return
        }
        p.inventory.giveItems(DIAMOND, amount)
        p.playSound(p.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
        val note = if (amount < requested) " (only $amount fit in your inventory)" else ""
        p.sendMessage("${PREFIX}Withdrew ${dia(amount)}$note. Balance: ${dia(bank.balance(p.uniqueId))}")
    }

    private fun pay(p: Player, args: Array<String>) {
        if (args.size < 2) {
            p.sendMessage("${PREFIX}Usage: /pay <player> <amount>")
            return
        }
        val target = bank.findByName(args[0])
        if (target == null) {
            p.sendMessage("${PREFIX}No player called ${args[0]} has joined this server.")
            return
        }
        if (target.uuid == p.uniqueId) {
            p.sendMessage("${PREFIX}You can't pay yourself.")
            return
        }
        val amount = parseAmount(p, args[1]) ?: return
        if (!bank.transfer(p.uniqueId, target.uuid, amount, Bank.TxType.PAY)) {
            p.sendMessage("${PREFIX}You don't have ${dia(amount)} in the bank.")
            return
        }
        p.sendMessage("${PREFIX}Sent ${dia(amount)} to ${ChatColor.WHITE}${target.name}${ChatColor.GRAY}.")
        Bukkit.getPlayer(target.uuid)?.let { online ->
            online.sendMessage("$PREFIX${ChatColor.WHITE}${p.name}${ChatColor.GRAY} sent you ${dia(amount)}!")
            online.playSound(online.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)
        }
    }

    private fun history(p: Player, args: Array<String>) {
        val page = if (args.isEmpty()) 1 else args[0].toIntOrNull()?.coerceAtLeast(1) ?: run {
            p.sendMessage("${PREFIX}Usage: /history [page]")
            return
        }
        val me = p.uniqueId
        val txns = bank.history(me, HISTORY_PAGE_SIZE, (page - 1) * HISTORY_PAGE_SIZE)
        if (txns.isEmpty()) {
            p.sendMessage(PREFIX + if (page == 1) "No transactions yet." else "No more transactions.")
            return
        }
        val time = SimpleDateFormat("MM-dd HH:mm")
        p.sendMessage("${ChatColor.AQUA}◆ ${ChatColor.WHITE}History ${ChatColor.GRAY}(page $page)")
        for (t in txns) {
            val incoming = me == t.to
            val amount = if (incoming) "${ChatColor.GREEN}+${t.amount}◆" else "${ChatColor.RED}-${t.amount}◆"
            p.sendMessage("${ChatColor.DARK_GRAY}${time.format(Date(t.createdAt))} $amount ${ChatColor.GRAY}${describe(t, incoming)}")
        }
        p.sendMessage("${ChatColor.DARK_GRAY}/history ${page + 1} for more")
    }

    private fun describe(t: Bank.Txn, incoming: Boolean): String {
        val other = if (incoming) t.fromName else t.toName
        return when (t.type) {
            Bank.TxType.DEPOSIT -> "deposited"
            Bank.TxType.WITHDRAW -> "withdrew"
            Bank.TxType.PAY -> if (incoming) "from $other" else "to $other"
            Bank.TxType.SHOP_PURCHASE ->
                // Older rows have no item name; fall back to the material.
                (if (incoming) "sold " else "bought ") +
                    "${t.itemAmount}× ${t.itemName ?: materialName(t.item ?: "")}${ChatColor.GRAY}" +
                    (if (incoming) " to " else " from ") + other
        }
    }

    private fun stats(p: Player) {
        val s = bank.stats(p.uniqueId)
        p.sendMessage("${ChatColor.AQUA}◆ ${ChatColor.WHITE}Your diamond stats")
        p.sendMessage("${ChatColor.GRAY}  Balance: ${dia(bank.balance(p.uniqueId))}")
        p.sendMessage("${ChatColor.GRAY}  Deposited ${dia(s.deposited)}, withdrew ${dia(s.withdrawn)}")
        p.sendMessage("${ChatColor.GRAY}  Received ${dia(s.received)}, sent ${dia(s.sent)}")
        p.sendMessage("${ChatColor.GRAY}  Shop sales ${dia(s.shopIncome)}, shop spending ${dia(s.shopSpent)}")
        p.sendMessage("${ChatColor.DARK_GRAY}  ${s.count} transactions total")
    }

    private fun baltop(sender: CommandSender) {
        sender.sendMessage("${ChatColor.AQUA}◆ ${ChatColor.WHITE}Richest players")
        bank.top(10).forEachIndexed { i, a ->
            sender.sendMessage("${ChatColor.GRAY}  ${i + 1}. ${ChatColor.WHITE}${a.name} ${dia(a.balance)}")
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String>? =
        when (command.name) {
            "deposit", "withdraw" -> if (args.size == 1) listOf("all") else emptyList()
            "pay" -> if (args.size == 1) null else emptyList() // null = online player names
            "balance" -> if (args.size == 1 && sender.hasPermission("diamondecon.admin")) null else emptyList()
            else -> emptyList()
        }

    companion object {
        private const val HISTORY_PAGE_SIZE = 8
        private val DIAMOND = ItemStack(Material.DIAMOND)
        private val DIAMOND_BLOCK = ItemStack(Material.DIAMOND_BLOCK)

        /** Positive whole number, or null after telling the sender why not. */
        fun parseAmount(sender: CommandSender, s: String): Long? {
            val n = s.toLongOrNull()
            if (n != null && n in 1..1_000_000_000L) return n
            sender.sendMessage("$PREFIX'$s' is not a valid amount.")
            return null
        }
    }
}
