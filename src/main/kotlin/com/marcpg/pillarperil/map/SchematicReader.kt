package com.marcpg.pillarperil.map

import net.kyori.adventure.nbt.BinaryTagIO
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
    fun read(file: File): Schematic? = runCatching {
        val nbt = BinaryTagIO.reader(Long.MAX_VALUE).read(file.inputStream(), BinaryTagIO.Compression.GZIP)

        val width = nbt.getInt("Width")
        val height = nbt.getInt("Height")
        val length = nbt.getInt("Length")
        val offset = nbt.getIntArray("Offset")
        val offsetX = offset.getOrElse(0) { 0 }
        val offsetY = offset.getOrElse(1) { 0 }
        val offsetZ = offset.getOrElse(2) { 0 }

        val paletteEntries = mutableListOf<Pair<Int, String>>()
        for ((state, index) in nbt.getCompound("Palette"))
            paletteEntries += ((index as? IntBinaryTag)?.intValue() ?: 0) to state

        val palette = MutableList((paletteEntries.maxOfOrNull { it.first } ?: -1) + 1) { "" }
        paletteEntries.forEach { (id, state) -> palette[id] = state }

        val blocks = when {
            nbt.contains("BlockData") -> nbt.getByteArray("BlockData").map { it.toInt() and 0xFF }.toIntArray()
            nbt.contains("Blocks") -> nbt.getIntArray("Blocks")
            else -> IntArray(0)
        }

        Schematic(width, height, length, offsetX, offsetY, offsetZ, palette, blocks)
    }.getOrNull()
}
