package com.swapflip.fortunepillars.map

import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import org.bukkit.World
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class SavedSchematic(
    val width: Int,
    val height: Int,
    val length: Int,
    val blocks: Int,
)

object SchematicSaver {
    // Saves the schematic between the two given corners. The schematic's local (0,0,0) is the min corner,
    // so pasting at that position always puts the arena exactly where the selection was made.
    fun save(world: World, first: BlockPos, second: BlockPos, file: File): SavedSchematic? = runCatching {
        val minX = min(first.x, second.x); val minY = min(first.y, second.y); val minZ = min(first.z, second.z)
        val maxX = max(first.x, second.x); val maxY = max(first.y, second.y); val maxZ = max(first.z, second.z)
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        val length = maxZ - minZ + 1

        val palette = linkedMapOf<String, Int>()
        val blocks = IntArray(width * height * length)
        var index = 0
        for (y in minY..maxY) {
            for (z in minZ..maxZ) {
                for (x in minX..maxX) {
                    val state = world.getBlockAt(x, y, z).blockData.asString
                    blocks[index++] = palette.getOrPut(state) { palette.size }
                }
            }
        }

        val paletteNbt = CompoundBinaryTag.builder().apply {
            palette.forEach { (state, id) -> putInt(state, id) }
        }.build()

        val nbt = CompoundBinaryTag.builder()
            .putInt("Version", 2)
            .putInt("DataVersion", 3955)
            .putInt("Width", width)
            .putInt("Height", height)
            .putInt("Length", length)
            .putIntArray("Offset", intArrayOf(0, 0, 0))
            .put("Palette", paletteNbt)
            .putIntArray("BlockData", blocks)
            .put("Metadata", CompoundBinaryTag.empty())
            .build()

        BinaryTagIO.writer().write(nbt, file.toPath(), BinaryTagIO.Compression.GZIP)
        SavedSchematic(width, height, length, blocks.size)
    }.getOrNull()
}
