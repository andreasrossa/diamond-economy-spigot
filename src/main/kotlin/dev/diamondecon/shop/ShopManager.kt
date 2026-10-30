package dev.diamondecon.shop

import dev.diamondecon.db.DbWorker
import dev.diamondecon.db.Shops
import dev.diamondecon.util.countItems
import dev.diamondecon.util.dia
import dev.diamondecon.util.dropItems
import dev.diamondecon.util.giveItems
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
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.PI

/**
 * The shop cache and the shops table. Main thread only; database work goes through [worker]
 * (except [load], which runs synchronously at startup).
 */
class ShopManager(private val plugin: JavaPlugin, private val db: Database, private val worker: DbWorker) {

    private val shops = HashMap<BlockKey, Shop>()

    val all: Collection<Shop> get() = shops.values

    fun load() {
        read().associateByTo(shops) { it.key }
        plugin.logger.info("Loaded ${shops.size} shops")
    }

    /**
     * Replaces the cache with what is in the database, e.g. after editing shops by hand.
     * If reading fails, the old cache stays in place. [onDone] gets the number of shops loaded.
     */
    fun reload(onDone: (Result<Int>) -> Unit) = worker.submit(::read) { result ->
        result.onSuccess { loaded ->
            shops.values.forEach(::despawnDecor)
            shops.clear()
            loaded.associateByTo(shops) { it.key }
            for (shop in shops.values.filter { isLoaded(it.key) }) {
                spawnDecor(shop)
                refresh(shop)
            }
            plugin.logger.info("Reloaded ${shops.size} shops")
        }
        onDone(result.map { shops.size })
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
            ).apply {
                val iconData = row[Shops.icon]
                if (iconData != null) {
                    icon = deserialize(iconData)
                    if (icon == null) plugin.logger.warning("Shop #$id: its icon could not be loaded, showing its item")
                }
            }
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

    /**
     * Saves a new shop, then caches it and spawns its decorations. [onDone] gets null if the chest was
     * broken while saving (the row is deleted again).
     */
    fun create(
        owner: UUID, ownerName: String, block: Block, item: ItemStack, name: String?, quantity: Int, price: Long,
        onDone: (Result<Shop?>) -> Unit,
    ) {
        val key = BlockKey.of(block)
        val proto = item.clone().apply { amount = 1 }
        val data = serialize(proto)
        worker.submit({
            transaction(db) {
                Shops.insertAndGetId {
                    it[ownerUuid] = owner
                    it[Shops.ownerName] = ownerName
                    it[world] = key.world
                    it[x] = key.x
                    it[y] = key.y
                    it[z] = key.z
                    it[Shops.item] = data
                    it[itemType] = proto.type.name
                    it[displayName] = name
                    it[Shops.quantity] = quantity
                    it[Shops.price] = price
                }.value
            }
        }) { result ->
            onDone(result.map { id ->
                val shop = Shop(id, owner, ownerName, key, proto, name, quantity, price, 0)
                if (isLoaded(key) && block(key).type !in SHOP_BLOCKS) {
                    delete(shop)
                    return@map null
                }
                shops[key] = shop
                if (isLoaded(key)) { // otherwise the decorations spawn when the chunk loads
                    spawnDecor(shop)
                    refresh(shop)
                }
                shop
            })
        }
    }

    /**
     * Closes the shop right away (cache and decorations) and deletes its row in the background.
     * It can't wait for the database: a block break has to be allowed or cancelled on the spot.
     */
    fun remove(shop: Shop) {
        if (shops[shop.key] === shop) shops.remove(shop.key)
        despawnDecor(shop)
        delete(shop)
    }

    private fun delete(shop: Shop) =
        worker.write("delete shop #${shop.id}; it will come back on the next restart or /shop reload") {
            transaction(db) { Shops.deleteWhere { id eq shop.id } }
        }

    fun setPrice(shop: Shop, price: Long, onDone: (Result<Unit>) -> Unit) =
        save(shop, { it[Shops.price] = price }, onDone) {
            shop.price = price
            updateLabel(shop)
        }

    fun setName(shop: Shop, name: String?, onDone: (Result<Unit>) -> Unit) =
        save(shop, { it[Shops.displayName] = name }, onDone) {
            shop.name = name
            updateLabel(shop)
        }

    /** Sets the floating icon to a copy of [icon], or back to the shop's item if null. */
    fun setIcon(shop: Shop, icon: ItemStack?, onDone: (Result<Unit>) -> Unit) {
        val proto = icon?.clone()?.apply { amount = 1 }
        val data = proto?.let(::serialize)
        save(shop, { it[Shops.icon] = data }, onDone) {
            shop.icon = proto
            shop.itemDisplay?.takeIf { it.isValid }?.setItemStack(shop.displayIcon)
        }
    }

    fun setOwner(shop: Shop, owner: UUID, ownerName: String, onDone: (Result<Unit>) -> Unit) =
        save(shop, {
            it[Shops.ownerUuid] = owner
            it[Shops.ownerName] = ownerName
        }, onDone) {
            shop.owner = owner
            shop.ownerName = ownerName
            updateLabel(shop)
        }

    /** Writes [changes] to the shop's row, then runs [apply] on the main thread so the cache matches. */
    private fun save(shop: Shop, changes: (UpdateStatement) -> Unit, onDone: (Result<Unit>) -> Unit, apply: () -> Unit) =
        worker.submit({ transaction(db) { Shops.update({ Shops.id eq shop.id }) { changes(it) } } }) { result ->
            onDone(result.map { apply() })
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
            worker.write("save stock for shop #${shop.id}") {
                transaction(db) { Shops.update({ Shops.id eq shop.id }) { it[Shops.stock] = stock } }
            }
        }
        updateLabel(shop)
    }

    /**
     * Puts [amount] reserved items back into the shop's chest. Whatever doesn't fit, or everything if the chest
     * is gone, is dropped at the shop. Works for removed shops too, since the chest still belongs to the owner.
     */
    fun returnItems(shop: Shop, amount: Int) {
        val inv = inventory(shop)
        val leftover = inv?.giveItems(shop.item, amount) ?: amount
        if (leftover > 0) {
            if (!isLoaded(shop.key)) {
                plugin.logger.severe("Could not return $leftover× ${shop.item.type} to shop #${shop.id} at ${shop.key}: chunk unloaded")
                return
            }
            dropItems(center(shop), shop.item, leftover)
        }
        if (shops[shop.key] === shop) refresh(shop)
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
            d.setItemStack(shop.displayIcon)
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
