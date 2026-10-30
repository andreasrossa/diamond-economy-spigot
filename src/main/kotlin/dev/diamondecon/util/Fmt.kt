package dev.diamondecon.util

import org.bukkit.ChatColor
import org.bukkit.inventory.ItemStack

val PREFIX = "${ChatColor.AQUA}◆ ${ChatColor.GRAY}"

fun dia(amount: Number) = "${ChatColor.AQUA}$amount◆${ChatColor.GRAY}"

val ItemStack.prettyName: String
    get() {
        val meta = itemMeta
        val name = if (meta != null && meta.hasDisplayName()) meta.displayName else materialName(type.name)
        return if (enchantments.isEmpty()) name else "$name${ChatColor.LIGHT_PURPLE} ✦"
    }

/** "DIAMOND_SWORD" -> "Diamond Sword" */
fun materialName(material: String): String =
    material.lowercase().split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
