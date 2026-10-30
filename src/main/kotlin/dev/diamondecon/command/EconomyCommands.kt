package dev.diamondecon.command

import dev.diamondecon.bank.Bank
import dev.diamondecon.db.DbWorker
import dev.diamondecon.util.PREFIX
import dev.diamondecon.util.countItems
import dev.diamondecon.util.dia
import dev.diamondecon.util.dropItems
import dev.diamondecon.util.giveOrDrop
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.Level

/** /balance, /deposit, /withdraw, /pay, /history, /stats, /baltop */
class EconomyCommands(
    private val plugin: JavaPlugin,
    private val bank: Bank,
    private val worker: DbWorker,
) : CommandExecutor, TabCompleter, Listener {

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val p = e.player
        worker.write("create account for ${p.name}") { bank.ensureAccount(p.uniqueId, p.name) }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
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
        return true
    }

    private fun balance(sender: CommandSender, args: Array<String>) {
        if (args.isNotEmpty()) {
            if (!sender.hasPermission("diamondecon.admin")) {
                sender.sendMessage("${PREFIX}You can only check your own balance.")
                return
            }
            worker.forSender(sender, "/balance", { bank.findByName(args[0]) }) { acc ->
                sender.sendMessage(
                    if (acc != null) "$PREFIX${ChatColor.WHITE}${acc.name}${ChatColor.GRAY} has ${dia(acc.balance)}"
                    else "${PREFIX}No player called ${args[0]}."
                )
            }
            return
        }
        if (sender !is Player) {
            sender.sendMessage("Usage: /balance <player>")
            return
        }
        val id = sender.uniqueId
        worker.forSender(sender, "/balance", { bank.balance(id) }) { balance ->
            sender.sendMessage("${PREFIX}Balance: ${dia(balance)}")
        }
    }

    /** Takes the diamonds first, then credits them. If the bank write fails, the diamonds are handed back. */
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
        val takenDiamonds = if (all) diamonds else amount.toInt()
        inv.takeItems(DIAMOND, takenDiamonds)
        inv.takeItems(DIAMOND_BLOCK, blocks)

        val id = p.uniqueId
        worker.submit({
            // Nothing may fail after the deposit commits, or the diamonds would be handed back as well.
            // Writes are serialized on the database thread, so the balance read before is exact.
            val before = bank.balance(id)
            bank.deposit(id, amount)
            before + amount
        }) { result ->
            result.onSuccess { balance ->
                p.playSound(p.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f)
                p.sendMessage("${PREFIX}Deposited ${dia(amount)}. Balance: ${dia(balance)}")
            }.onFailure { e ->
                plugin.logger.log(Level.SEVERE, "/deposit of $amount for ${p.name} failed, returning the diamonds", e)
                if (p.isOnline) {
                    p.giveOrDrop(DIAMOND, takenDiamonds)
                    p.giveOrDrop(DIAMOND_BLOCK, blocks)
                    p.sendMessage("$PREFIX${ChatColor.RED}Something went wrong, your diamonds were given back.")
                } else {
                    // A logged-out player's inventory is already saved; changing it now would lose the diamonds.
                    plugin.logger.severe("${p.name} logged out; dropping their $amount◆ at ${p.location}")
                    dropItems(p.location, DIAMOND, takenDiamonds)
                    dropItems(p.location, DIAMOND_BLOCK, blocks)
                }
            }
        }
    }

    private sealed interface WithdrawResult {
        data class Done(val amount: Int, val wanted: Long, val balance: Long) : WithdrawResult
        data class TooLittle(val balance: Long) : WithdrawResult
    }

    /** Debits first, then hands out the diamonds. If the player left in between, the debit is refunded. */
    private fun withdraw(p: Player, args: Array<String>) {
        if (args.isEmpty()) {
            p.sendMessage("${PREFIX}Usage: /withdraw <amount|all>")
            return
        }
        val all = args[0].equals("all", ignoreCase = true)
        val requested = if (all) null else parseAmount(p, args[0]) ?: return
        val room = p.inventory.roomFor(DIAMOND)
        if (room == 0) {
            p.sendMessage("${PREFIX}Your inventory is full.")
            return
        }
        val id = p.uniqueId
        worker.forSender(p, "/withdraw", {
            val balance = bank.balance(id)
            val wanted = requested ?: balance
            val amount = minOf(wanted, room.toLong())
            // Nothing may fail after the withdrawal commits, or the player would lose the diamonds.
            if (wanted == 0L || wanted > balance || !bank.withdraw(id, amount)) {
                WithdrawResult.TooLittle(balance)
            } else {
                WithdrawResult.Done(amount.toInt(), wanted, balance - amount)
            }
        }) { result ->
            when (result) {
                is WithdrawResult.TooLittle -> p.sendMessage("${PREFIX}You only have ${dia(result.balance)} in the bank.")
                is WithdrawResult.Done -> {
                    if (!p.isOnline) {
                        worker.write("refund an undelivered withdrawal of ${result.amount}◆ to ${p.name}") {
                            bank.refundWithdrawal(id, result.amount.toLong())
                        }
                        return@forSender
                    }
                    p.giveOrDrop(DIAMOND, result.amount)
                    p.playSound(p.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
                    val note = if (result.amount < result.wanted) " (only ${result.amount} fit in your inventory)" else ""
                    p.sendMessage("${PREFIX}Withdrew ${dia(result.amount)}$note. Balance: ${dia(result.balance)}")
                }
            }
        }
    }

    private sealed interface PayResult {
        data object NoSuchPlayer : PayResult
        data object Self : PayResult
        data object TooLittle : PayResult
        data class Done(val target: Bank.Account) : PayResult
    }

    private fun pay(p: Player, args: Array<String>) {
        if (args.size < 2) {
            p.sendMessage("${PREFIX}Usage: /pay <player> <amount>")
            return
        }
        val amount = parseAmount(p, args[1]) ?: return
        val id = p.uniqueId
        worker.forSender(p, "/pay", {
            val target = bank.findByName(args[0])
            when {
                target == null -> PayResult.NoSuchPlayer
                target.uuid == id -> PayResult.Self
                !bank.transfer(id, target.uuid, amount, Bank.TxType.PAY) -> PayResult.TooLittle
                else -> PayResult.Done(target)
            }
        }) { result ->
            when (result) {
                PayResult.NoSuchPlayer -> p.sendMessage("${PREFIX}No player called ${args[0]} has joined this server.")
                PayResult.Self -> p.sendMessage("${PREFIX}You can't pay yourself.")
                PayResult.TooLittle -> p.sendMessage("${PREFIX}You don't have ${dia(amount)} in the bank.")
                is PayResult.Done -> {
                    p.sendMessage("${PREFIX}Sent ${dia(amount)} to ${ChatColor.WHITE}${result.target.name}${ChatColor.GRAY}.")
                    Bukkit.getPlayer(result.target.uuid)?.let { online ->
                        online.sendMessage("$PREFIX${ChatColor.WHITE}${p.name}${ChatColor.GRAY} sent you ${dia(amount)}!")
                        online.playSound(online.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)
                    }
                }
            }
        }
    }

    private fun history(p: Player, args: Array<String>) {
        val page = if (args.isEmpty()) 1 else args[0].toIntOrNull()?.coerceAtLeast(1) ?: run {
            p.sendMessage("${PREFIX}Usage: /history [page]")
            return
        }
        val me = p.uniqueId
        worker.forSender(p, "/history", { bank.history(me, HISTORY_PAGE_SIZE, (page - 1) * HISTORY_PAGE_SIZE) }) { txns ->
            showHistory(p, page, txns)
        }
    }

    private fun showHistory(p: Player, page: Int, txns: List<Bank.Txn>) {
        val me = p.uniqueId
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
            Bank.TxType.REFUND -> when {
                t.from == null -> "refund, the withdrawal wasn't delivered"
                incoming -> "refund from $other, the purchase wasn't delivered"
                else -> "refunded to $other"
            }
        }
    }

    private fun stats(p: Player) {
        val id = p.uniqueId
        worker.forSender(p, "/stats", { bank.stats(id) to bank.balance(id) }) { (s, balance) ->
            showStats(p, s, balance)
        }
    }

    private fun showStats(p: Player, s: Bank.Stats, balance: Long) {
        p.sendMessage("${ChatColor.AQUA}◆ ${ChatColor.WHITE}Your diamond stats")
        p.sendMessage("${ChatColor.GRAY}  Balance: ${dia(balance)}")
        p.sendMessage("${ChatColor.GRAY}  Deposited ${dia(s.deposited)}, withdrew ${dia(s.withdrawn)}")
        p.sendMessage("${ChatColor.GRAY}  Received ${dia(s.received)}, sent ${dia(s.sent)}")
        p.sendMessage("${ChatColor.GRAY}  Shop sales ${dia(s.shopIncome)}, shop spending ${dia(s.shopSpent)}")
        p.sendMessage("${ChatColor.DARK_GRAY}  ${s.count} transactions total")
    }

    private fun baltop(sender: CommandSender) {
        worker.forSender(sender, "/baltop", { bank.top(10) }) { top ->
            sender.sendMessage("${ChatColor.AQUA}◆ ${ChatColor.WHITE}Richest players")
            top.forEachIndexed { i, a ->
                sender.sendMessage("${ChatColor.GRAY}  ${i + 1}. ${ChatColor.WHITE}${a.name} ${dia(a.balance)}")
            }
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
