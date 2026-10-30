package dev.diamondecon.shop;

import org.bukkit.Location;
import org.bukkit.block.Block;

public record BlockKey(String world, int x, int y, int z) {

    public static BlockKey of(Block b) {
        return new BlockKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    /** Null for locations without a world (e.g. virtual inventories). */
    public static BlockKey of(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return new BlockKey(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    @Override
    public String toString() {
        return world + " " + x + ", " + y + ", " + z;
    }
}
