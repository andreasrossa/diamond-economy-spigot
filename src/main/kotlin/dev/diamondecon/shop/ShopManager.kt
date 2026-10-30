package dev.diamondecon.shop

import dev.diamondecon.getUuid
import dev.diamondecon.insert
import dev.diamondecon.query
import dev.diamondecon.update
import dev.diamondecon.util.countItems
import dev.diamondecon.util.dia
import dev.diamondecon.util.prettyName
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.logging.Level
import kotlin.math.PI

class ShopManager(private val plugin: JavaPlugin, private val c: Connection) {

    private val shops = HashMap<BlockKey, Shop>()

    val all: Collection<Shop> get() = shops.values

    fun load() {
        val loaded = c.query("SELECT id, owner_uuid, owner_name, world, x, y, z, item, quantity, price, stock FROM shops") { rs ->
            val id = rs.getLong(1)
            val item = deserialize(rs.getString(8))
            if (item == null) {
                plugin.logger.warning("Skipping shop #$id: its item could not be loaded")
                return@query null
            }
            val key = BlockKey(rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7))
            Shop(id, rs.getUuid(2)!!, rs.getString(3), key, item, rs.getInt(9), rs.getLong(10), rs.getInt(11))
        }
        loaded.filterNotNull().associateByTo(shops) { it.key }
        plugin.logger.info("Loaded ${shops.size} shops")
    }

    /** Spawns decorations for already-loaded chunks and starts the spin animation. */
    fun start() {
        shops.values.filter { isLoaded(it.key) }.forEach(::spawnDecor)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { spin() }, SPIN_TICKS, SPIN_TICKS)
    }

    fun stop() = shops.values.forEach(::despawnDecor)

    fun at(block: Block): Shop? = shops[BlockKey.of(block)]

    fun at(key: BlockKey?): Shop? = key?.let { shops[it] }

    fun ownedBy(owner: UUID): List<Shop> = shops.values.filter { it.owner == owner }

    /** True if a chest placed at [block] could merge into a double chest with a shop. */
    fun touchesShopChest(block: Block): Boolean =
        listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST).any { face ->
            val n = block.getRelative(face)
            n.type == block.type && at(n) != null
        }

    fun create(owner: UUID, ownerName: String, block: Block, item: ItemStack, quantity: Int, price: Long): Shop {
        val key = BlockKey.of(block)
        val proto = item.clone().apply { amount = 1 }
        val id = c.insert(
            """
            INSERT INTO shops(owner_uuid, owner_name, world, x, y, z, item, item_type, quantity, price, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            owner, ownerName, key.world, key.x, key.y, key.z,
            serialize(proto), proto.type.name, quantity, price, System.currentTimeMillis(),
        )
        val shop = Shop(id, owner, ownerName, key, proto, quantity, price, 0)
        shops[key] = shop
        spawnDecor(shop)
        refresh(shop)
        return shop
    }

    fun remove(shop: Shop) {
        c.update("DELETE FROM shops WHERE id = ?", shop.id)
        shops.remove(shop.key)
        despawnDecor(shop)
    }

    fun setPrice(shop: Shop, price: Long) {
        c.update("UPDATE shops SET price = ? WHERE id = ?", price, shop.id)
        shop.price = price
        updateLabel(shop)
    }

    /** The live inventory of the shop block, or null if it is unloaded or no longer a container. */
    fun inventory(shop: Shop): Inventory? {
        if (!isLoaded(shop.key)) return null
        val block = block(shop.key)
        if (block.type !in SHOP_BLOCKS) return null
        return (block.state as? Container)?.inventory
    }

    /** Recounts stock, updates the floating label and the stock column the dashboard reads. */
    fun refresh(shop: Shop) {
        val inv = inventory(shop) ?: return
        val stock = inv.countItems(shop.item)
        if (stock != shop.stock) {
            shop.stock = stock
            try {
                c.update("UPDATE shops SET stock = ? WHERE id = ?", stock, shop.id)
            } catch (e: SQLException) {
                plugin.logger.log(Level.WARNING, "Could not save stock for shop #${shop.id}", e)
            }
        }
        updateLabel(shop)
    }

    fun center(shop: Shop): Location = block(shop.key).location.add(0.5, 0.5, 0.5)

    // --- decorations -------------------------------------------------------------------------

    fun onChunkLoad(chunk: Chunk) {
        val inChunk = inChunk(chunk)
        if (inChunk.isEmpty()) return
        // Spawn a tick later; spawning entities mid chunk-load is fragile.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            for (shop in inChunk) {
                if (shops[shop.key] === shop && isLoaded(shop.key)) {
                    spawnDecor(shop)
                    refresh(shop)
                }
            }
        })
    }

    fun onChunkUnload(chunk: Chunk) = inChunk(chunk).forEach(::despawnDecor)

    private fun inChunk(chunk: Chunk): List<Shop> = shops.values.filter {
        it.key.world == chunk.world.name && it.key.chunkX == chunk.x && it.key.chunkZ == chunk.z
    }

    private fun spawnDecor(shop: Shop) {
        despawnDecor(shop)
        val world = Bukkit.getWorld(shop.key.world) ?: return
        val base = block(shop.key).location.add(0.5, 0.0, 0.5)

        shop.itemDisplay = world.spawn(base.clone().add(0.0, 1.35, 0.0), ItemDisplay::class.java) { d ->
            d.isPersistent = false
            d.setItemStack(shop.item)
            d.brightness = Display.Brightness(15, 15)
            d.transformation = spinTransform(shop.angle)
        }
        shop.label = world.spawn(base.clone().add(0.0, 1.75, 0.0), TextDisplay::class.java) { d ->
            d.isPersistent = false
            d.billboard = Display.Billboard.CENTER
            d.backgroundColor = Color.fromARGB(120, 45, 20, 60)
            d.isShadowed = true
            d.lineWidth = 200
            d.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(0.6f, 0.6f, 0.6f), AxisAngle4f())
        }
        updateLabel(shop)
    }

    private fun despawnDecor(shop: Shop) {
        shop.itemDisplay?.remove()
        shop.label?.remove()
        shop.itemDisplay = null
        shop.label = null
    }

    private fun updateLabel(shop: Shop) {
        val label = shop.label?.takeIf { it.isValid } ?: return
        val bundles = shop.stock / shop.quantity
        val stockLine = if (bundles > 0) "${ChatColor.GRAY}in stock: ${ChatColor.WHITE}$bundles"
        else "${ChatColor.RED}sold out ${ChatColor.GRAY}:("
        label.text = """
            ${ChatColor.LIGHT_PURPLE}✿ ${ChatColor.WHITE}${shop.ownerName}'s Shop ${ChatColor.LIGHT_PURPLE}✿
            ${ChatColor.AQUA}${shop.quantity}× ${ChatColor.WHITE}${shop.item.prettyName}
            ${ChatColor.GRAY}for ${dia(shop.price)}
            $stockLine""".trimIndent()
    }

    private fun spin() {
        for (shop in shops.values) {
            val d = shop.itemDisplay?.takeIf { it.isValid } ?: continue
            shop.angle = (shop.angle + (PI / 2).toFloat()) % (2 * PI).toFloat()
            d.interpolationDelay = 0
            d.interpolationDuration = SPIN_TICKS.toInt()
            d.transformation = spinTransform(shop.angle)
        }
    }

    companion object {
        val SHOP_BLOCKS = setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL)
        private const val ITEM_SCALE = 0.5f
        private const val SPIN_TICKS = 20L

        private fun spinTransform(angle: Float) = Transformation(
            Vector3f(), AxisAngle4f(angle, 0f, 1f, 0f),
            Vector3f(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE), AxisAngle4f(),
        )

        private fun isLoaded(key: BlockKey): Boolean =
            Bukkit.getWorld(key.world)?.isChunkLoaded(key.chunkX, key.chunkZ) == true

        private fun block(key: BlockKey): Block = Bukkit.getWorld(key.world)!!.getBlockAt(key.x, key.y, key.z)

        private fun serialize(item: ItemStack): String =
            YamlConfiguration().apply { set("item", item) }.saveToString()

        private fun deserialize(data: String): ItemStack? = try {
            YamlConfiguration().apply { loadFromString(data) }.getItemStack("item")
        } catch (e: InvalidConfigurationException) {
            null
        }
    }
}
