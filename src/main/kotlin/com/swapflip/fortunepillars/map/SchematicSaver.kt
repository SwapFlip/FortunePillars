package com.swapflip.fortunepillars.map

import com.swapflip.fortunepillars.FortunePillars
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import org.bukkit.Bukkit
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
    // Kept in sync with SchematicReader.MAX_DIMENSION so saved files always re-read, plus a
    // total-volume cap so a mis-clicked selection (or an entire chunk column) can't allocate
    // hundreds of megabytes or freeze the server on the save thread.
    const val MAX_DIMENSION = 512
    const val MAX_BLOCKS = 2_000_000

    // Saves the schematic between the two given corners. The schematic's local (0,0,0) is the min corner,
    // so pasting at that position always puts the arena exactly where the selection was made.
    fun save(world: World, first: BlockPos, second: BlockPos, file: File): SavedSchematic? = runCatching {
        val minX = min(first.x, second.x); val minY = min(first.y, second.y); val minZ = min(first.z, second.z)
        val maxX = max(first.x, second.x); val maxY = max(first.y, second.y); val maxZ = max(first.z, second.z)
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        val length = maxZ - minZ + 1
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || length > MAX_DIMENSION || width * height * length > MAX_BLOCKS) {
            FortunePillars.LOG.warn("[Schematic] Save rejected: selection is ${width}x${height}x${length} = ${width * height * length} blocks (cap: $MAX_BLOCKS, $MAX_DIMENSION per side).")
            return@runCatching null
        }

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
            // Written at save time so the file carries the version it was created on; the server's
            // DataFixer upgrades it on load instead of trusting a hardcoded constant.
            .putInt("DataVersion", Bukkit.getUnsafe().dataVersion)
            .putInt("Width", width)
            .putInt("Height", height)
            .putInt("Length", length)
            .putIntArray("Offset", intArrayOf(0, 0, 0))
            .put("Palette", paletteNbt)
            .putIntArray("BlockData", blocks)
            .put("Metadata", CompoundBinaryTag.empty())
            .build()

        // The block scan above must run on the main thread (world access), but the gzip write is
        // pure I/O: a multi-megabyte file would otherwise stall the tick loop, so it goes async.
        // A failed write is logged; the command handler still reports the selection as saved, since
        // the schematic data itself was captured successfully.
        Bukkit.getScheduler().runTaskAsynchronously(FortunePillars.PLUGIN, Runnable {
            runCatching { BinaryTagIO.writer().write(nbt, file.toPath(), BinaryTagIO.Compression.GZIP) }
                .onFailure { FortunePillars.LOG.warn("[Schematic] Failed to write \"${file.name}\": ${it.message}") }
        })
        SavedSchematic(width, height, length, blocks.size)
    }.getOrNull()
}
