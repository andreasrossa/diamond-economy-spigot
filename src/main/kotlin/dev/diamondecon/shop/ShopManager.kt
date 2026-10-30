package dev.diamondecon.shop

import dev.diamondecon.db.Shops
import dev.diamondecon.util.countItems
import dev.diamondecon.util.dia
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.sql.SQLException
import java.util.UUID
import java.util.logging.Level
import kotlin.math.PI

class ShopManager(private val plugin: JavaPlugin, private val db: Database) {

    private val shops = HashMap<BlockKey, Shop>()

    val all: Collection<Shop> get() = shops.values

    fun load() {
        read().associateByTo(shops) { it.key }
        plugin.logger.info("Loaded ${shops.size} shops")
    }

    /**
     * Replaces the cache with what is in the database, e.g. after editing shops by hand.
     * If reading fails, this throws and the old cache stays in place.
     * @return the number of shops loaded
     */
    fun reload(): Int {
        val loaded = read()
        shops.values.forEach(::despawnDecor)
        shops.clear()
        loaded.associateByTo(shops) { it.key }
        for (shop in shops.values.filter { isLoaded(it.key) }) {
            spawnDecor(shop)
            refresh(shop)
        }
        plugin.logger.info("Reloaded ${shops.size} shops")
        return shops.size
    }

    private fun read(): List<Shop> = transaction(db) {
        Shops.selectAll().mapNotNull { row ->
            val id = row[Shops.id].value
            val item = deserialize(row[Shops.item])
            if (item == null) {
                plugin.logger.warning("Skipping shop #$id: its item could not be loaded")
                return@mapNotNull null
            }
            val key = BlockKey(row[Shops.world], row[Shops.x], row[Shops.y], row[Shops.z])
            Shop(
                id, row[Shops.ownerUuid], row[Shops.ownerName], key, item, row[Shops.displayName],
                row[Shops.quantity], row[Shops.price], row[Shops.stock],
            )
        }
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

    fun create(owner: UUID, ownerName: String, block: Block, item: ItemStack, name: String?, quantity: Int, price: Long): Shop {
        val key = BlockKey.of(block)
        val proto = item.clone().apply { amount = 1 }
        val id = transaction(db) {
            Shops.insertAndGetId {
                it[ownerUuid] = owner
                it[Shops.ownerName] = ownerName
                it[world] = key.world
                it[x] = key.x
                it[y] = key.y
                it[z] = key.z
                it[Shops.item] = serialize(proto)
                it[itemType] = proto.type.name
                it[displayName] = name
                it[Shops.quantity] = quantity
                it[Shops.price] = price
            }.value
        }
        val shop = Shop(id, owner, ownerName, key, proto, name, quantity, price, 0)
        shops[key] = shop
        spawnDecor(shop)
        refresh(shop)
        return shop
    }

    fun remove(shop: Shop) {
        transaction(db) { Shops.deleteWhere { id eq shop.id } }
        shops.remove(shop.key)
        despawnDecor(shop)
    }

    fun setPrice(shop: Shop, price: Long) {
        transaction(db) { Shops.update({ Shops.id eq shop.id }) { it[Shops.price] = price } }
        shop.price = price
        updateLabel(shop)
    }

    fun setName(shop: Shop, name: String?) {
        transaction(db) { Shops.update({ Shops.id eq shop.id }) { it[displayName] = name } }
        shop.name = name
        updateLabel(shop)
    }

    fun setOwner(shop: Shop, owner: UUID, ownerName: String) {
        transaction(db) {
            Shops.update({ Shops.id eq shop.id }) {
                it[ownerUuid] = owner
                it[Shops.ownerName] = ownerName
            }
        }
        shop.owner = owner
        shop.ownerName = ownerName
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
                transaction(db) { Shops.update({ Shops.id eq shop.id }) { it[Shops.stock] = stock } }
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
            ${ChatColor.AQUA}${shop.quantity}× ${ChatColor.WHITE}${shop.displayName}
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
