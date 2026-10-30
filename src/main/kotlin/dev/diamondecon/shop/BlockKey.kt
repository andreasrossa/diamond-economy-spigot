package dev.diamondecon.shop

import org.bukkit.Location
import org.bukkit.block.Block

data class BlockKey(val world: String, val x: Int, val y: Int, val z: Int) {

    val chunkX get() = x shr 4
    val chunkZ get() = z shr 4

    override fun toString() = "$world $x, $y, $z"

    companion object {
        fun of(b: Block) = BlockKey(b.world.name, b.x, b.y, b.z)

        /** Null for locations without a world (e.g. virtual inventories). */
        fun of(l: Location?): BlockKey? {
            val world = l?.world ?: return null
            return BlockKey(world.name, l.blockX, l.blockY, l.blockZ)
        }
    }
}
