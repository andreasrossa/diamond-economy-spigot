package dev.diamondecon.shop;

import dev.diamondecon.util.Fmt;
import dev.diamondecon.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class ShopManager {

    public static final Set<Material> SHOP_BLOCKS = Set.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);
    private static final float ITEM_SCALE = 0.5f;
    private static final int SPIN_TICKS = 20;

    private final JavaPlugin plugin;
    private final Connection c;
    private final Map<BlockKey, Shop> shops = new HashMap<>();

    public ShopManager(JavaPlugin plugin, Connection connection) {
        this.plugin = plugin;
        this.c = connection;
    }

    public void load() throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id, owner_uuid, owner_name, world, x, y, z, item, quantity, price, stock FROM shops")) {
            while (rs.next()) {
                long id = rs.getLong(1);
                ItemStack item = deserialize(rs.getString(8));
                if (item == null) {
                    plugin.getLogger().warning("Skipping shop #" + id + ": its item could not be loaded");
                    continue;
                }
                BlockKey key = new BlockKey(rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7));
                shops.put(key, new Shop(id, UUID.fromString(rs.getString(2)), rs.getString(3), key, item,
                        rs.getInt(9), rs.getLong(10), rs.getInt(11)));
            }
        }
        plugin.getLogger().info("Loaded " + shops.size() + " shops");
    }

    /** Spawns decorations for already-loaded chunks and starts the spin animation. */
    public void start() {
        for (Shop shop : shops.values()) {
            if (isLoaded(shop.key)) spawnDecor(shop);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::spin, SPIN_TICKS, SPIN_TICKS);
    }

    public void stop() {
        shops.values().forEach(this::despawnDecor);
    }

    public Shop at(Block block) {
        return shops.get(BlockKey.of(block));
    }

    public Shop at(BlockKey key) {
        return key == null ? null : shops.get(key);
    }

    public Collection<Shop> all() {
        return shops.values();
    }

    public List<Shop> ownedBy(UUID owner) {
        List<Shop> out = new ArrayList<>();
        for (Shop s : shops.values()) if (s.owner.equals(owner)) out.add(s);
        return out;
    }

    /** True if a chest placed at {@code block} could merge into a double chest with a shop. */
    public boolean touchesShopChest(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = block.getRelative(face);
            if (n.getType() == block.getType() && at(n) != null) return true;
        }
        return false;
    }

    public Shop create(UUID owner, String ownerName, Block block, ItemStack item, int quantity, long price)
            throws SQLException {
        BlockKey key = BlockKey.of(block);
        ItemStack proto = item.clone();
        proto.setAmount(1);
        long id;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shops(owner_uuid, owner_name, world, x, y, z, item, item_type, quantity, price, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.toString());
            ps.setString(2, ownerName);
            ps.setString(3, key.world());
            ps.setInt(4, key.x());
            ps.setInt(5, key.y());
            ps.setInt(6, key.z());
            ps.setString(7, serialize(proto));
            ps.setString(8, proto.getType().name());
            ps.setInt(9, quantity);
            ps.setLong(10, price);
            ps.setLong(11, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        }
        Shop shop = new Shop(id, owner, ownerName, key, proto, quantity, price, 0);
        shops.put(key, shop);
        spawnDecor(shop);
        refresh(shop);
        return shop;
    }

    public void remove(Shop shop) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM shops WHERE id = ?")) {
            ps.setLong(1, shop.id);
            ps.executeUpdate();
        }
        shops.remove(shop.key);
        despawnDecor(shop);
    }

    public void setPrice(Shop shop, long price) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE shops SET price = ? WHERE id = ?")) {
            ps.setLong(1, price);
            ps.setLong(2, shop.id);
            ps.executeUpdate();
        }
        shop.price = price;
        updateLabel(shop);
    }

    /** The live inventory of the shop block, or null if it is unloaded or no longer a container. */
    public Inventory inventory(Shop shop) {
        if (!isLoaded(shop.key)) return null;
        Block block = block(shop.key);
        if (!SHOP_BLOCKS.contains(block.getType())) return null;
        BlockState state = block.getState();
        return state instanceof Container container ? container.getInventory() : null;
    }

    /** Recounts stock, updates the floating label and the stock column the dashboard reads. */
    public void refresh(Shop shop) {
        Inventory inv = inventory(shop);
        if (inv == null) return;
        int stock = Items.count(inv, shop.item);
        if (stock != shop.stock) {
            shop.stock = stock;
            try (PreparedStatement ps = c.prepareStatement("UPDATE shops SET stock = ? WHERE id = ?")) {
                ps.setInt(1, stock);
                ps.setLong(2, shop.id);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not save stock for shop #" + shop.id, e);
            }
        }
        updateLabel(shop);
    }

    // --- decorations -------------------------------------------------------------------------

    public void onChunkLoad(Chunk chunk) {
        // Spawn a tick later; spawning entities mid chunk-load is fragile.
        List<Shop> inChunk = inChunk(chunk);
        if (inChunk.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Shop shop : inChunk) {
                if (shops.get(shop.key) == shop && isLoaded(shop.key)) {
                    spawnDecor(shop);
                    refresh(shop);
                }
            }
        });
    }

    public void onChunkUnload(Chunk chunk) {
        inChunk(chunk).forEach(this::despawnDecor);
    }

    public Location center(Shop shop) {
        return block(shop.key).getLocation().add(0.5, 0.5, 0.5);
    }

    private List<Shop> inChunk(Chunk chunk) {
        String world = chunk.getWorld().getName();
        List<Shop> out = new ArrayList<>();
        for (Shop s : shops.values()) {
            if (s.key.world().equals(world) && s.key.chunkX() == chunk.getX() && s.key.chunkZ() == chunk.getZ()) {
                out.add(s);
            }
        }
        return out;
    }

    private void spawnDecor(Shop shop) {
        despawnDecor(shop);
        World world = Bukkit.getWorld(shop.key.world());
        if (world == null) return;
        Location base = block(shop.key).getLocation().add(0.5, 0, 0.5);

        shop.itemDisplay = world.spawn(base.clone().add(0, 1.35, 0), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(shop.item);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(spinTransform(shop.angle));
        });
        shop.label = world.spawn(base.clone().add(0, 1.75, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(120, 45, 20, 60));
            d.setShadowed(true);
            d.setLineWidth(200);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
        });
        updateLabel(shop);
    }

    private void despawnDecor(Shop shop) {
        if (shop.itemDisplay != null) shop.itemDisplay.remove();
        if (shop.label != null) shop.label.remove();
        shop.itemDisplay = null;
        shop.label = null;
    }

    private void updateLabel(Shop shop) {
        if (shop.label == null || !shop.label.isValid()) return;
        int bundles = shop.stock / shop.quantity;
        String stockLine = bundles > 0
                ? ChatColor.GRAY + "in stock: " + ChatColor.WHITE + bundles
                : ChatColor.RED + "sold out " + ChatColor.GRAY + ":(";
        shop.label.setText(
                ChatColor.LIGHT_PURPLE + "✿ " + ChatColor.WHITE + shop.ownerName + "'s Shop " + ChatColor.LIGHT_PURPLE + "✿\n"
                        + ChatColor.AQUA + shop.quantity + "× " + ChatColor.WHITE + Fmt.itemName(shop.item) + "\n"
                        + ChatColor.GRAY + "for " + Fmt.dia(shop.price) + "\n"
                        + stockLine);
    }

    private void spin() {
        for (Shop shop : shops.values()) {
            ItemDisplay d = shop.itemDisplay;
            if (d == null || !d.isValid()) continue;
            shop.angle = (shop.angle + (float) (Math.PI / 2)) % (float) (Math.PI * 2);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(SPIN_TICKS);
            d.setTransformation(spinTransform(shop.angle));
        }
    }

    private static Transformation spinTransform(float angle) {
        return new Transformation(new Vector3f(), new AxisAngle4f(angle, 0, 1, 0),
                new Vector3f(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE), new AxisAngle4f());
    }

    // --- helpers -----------------------------------------------------------------------------

    private static boolean isLoaded(BlockKey key) {
        World world = Bukkit.getWorld(key.world());
        return world != null && world.isChunkLoaded(key.chunkX(), key.chunkZ());
    }

    private static Block block(BlockKey key) {
        return Bukkit.getWorld(key.world()).getBlockAt(key.x(), key.y(), key.z());
    }

    private static String serialize(ItemStack item) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", item);
        return yaml.saveToString();
    }

    private static ItemStack deserialize(String data) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(data);
        } catch (InvalidConfigurationException e) {
            return null;
        }
        return yaml.getItemStack("item");
    }
}
