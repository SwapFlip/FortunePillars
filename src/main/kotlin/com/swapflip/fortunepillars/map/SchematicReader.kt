package com.swapflip.fortunepillars.map

import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.ByteArrayBinaryTag
import net.kyori.adventure.nbt.IntArrayBinaryTag
import net.kyori.adventure.nbt.IntBinaryTag
import java.io.File

data class Schematic(
    val width: Int,
    val height: Int,
    val length: Int,
    val offsetX: Int,
    val offsetY: Int,
    val offsetZ: Int,
    val palette: List<String>,
    val blocks: IntArray,
)

object SchematicReader {
    // Sanity caps against corrupt/hostile files: anything beyond this is rejected instead of
    // allocating gigabytes or dividing by zero on paste.
    private const val MAX_DIMENSION = 512
    private const val MAX_PALETTE_SIZE = 65_536

    fun read(file: File): Schematic? = runCatching {
        val nbt = BinaryTagIO.reader(Long.MAX_VALUE).read(file.inputStream(), BinaryTagIO.Compression.GZIP)

        val width = nbt.getInt("Width")
        val height = nbt.getInt("Height")
        val length = nbt.getInt("Length")
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION || length !in 1..MAX_DIMENSION)
            return null

        val offset = nbt.getIntArray("Offset")
        val offsetX = offset.getOrElse(0) { 0 }
        val offsetY = offset.getOrElse(1) { 0 }
        val offsetZ = offset.getOrElse(2) { 0 }

        val paletteEntries = mutableListOf<Pair<Int, String>>()
        for ((state, index) in nbt.getCompound("Palette"))
            paletteEntries += ((index as? IntBinaryTag)?.intValue() ?: 0) to state

        // A hostile palette index must not drive an unbounded allocation.
        val maxIndex = paletteEntries.maxOfOrNull { it.first } ?: return null
        if (maxIndex >= MAX_PALETTE_SIZE) return null
        val palette = MutableList(maxIndex + 1) { "" }
        paletteEntries.forEach { (id, state) -> palette[id] = state }

        val blocks = when (val tag = nbt.get("BlockData")) {
            is ByteArrayBinaryTag -> tag.value().map { it.toInt() and 0xFF }.toIntArray()
            is IntArrayBinaryTag -> tag.value()
            else -> return null // Anything else means a corrupt or unsupported file: never "paste nothing".
        }

        // The block array must cover exactly the volume, or the paste would silently land outside the box.
        if (blocks.size != width * height * length) return null

        // BlockEntities/Entities tags from third-party files are intentionally ignored: there is
        // no public block-entity NBT API on this Paper version, so tile data (chest loot, signs)
        // cannot round-trip. Pasting block data alone is safe - tiles simply come up empty.

        Schematic(width, height, length, offsetX, offsetY, offsetZ, palette, blocks)
    }.getOrNull()
}
