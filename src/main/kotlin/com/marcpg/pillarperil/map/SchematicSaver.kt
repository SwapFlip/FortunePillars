package com.marcpg.pillarperil.map

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
    fun save(world: World, origin: BlockPos, corner: BlockPos, file: File): SavedSchematic? = runCatching {
        val minX = min(origin.x, corner.x); val minY = min(origin.y, corner.y); val minZ = min(origin.z, corner.z)
        val maxX = max(origin.x, corner.x); val maxY = max(origin.y, corner.y); val maxZ = max(origin.z, corner.z)
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
            .putIntArray("Offset", intArrayOf(origin.x - minX, origin.y - minY, origin.z - minZ))
            .put("Palette", paletteNbt)
            .putIntArray("BlockData", blocks)
            .put("Metadata", CompoundBinaryTag.empty())
            .build()

        BinaryTagIO.writer().write(nbt, file.toPath(), BinaryTagIO.Compression.GZIP)
        SavedSchematic(width, height, length, blocks.size)
    }.getOrNull()
}
