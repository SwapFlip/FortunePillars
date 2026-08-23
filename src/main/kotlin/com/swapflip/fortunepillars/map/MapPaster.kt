package com.swapflip.fortunepillars.map

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import kotlin.math.max
import kotlin.math.min

data class MapBounds(
    val minX: Int, val minY: Int, val minZ: Int,
    val maxX: Int, val maxY: Int, val maxZ: Int,
)

object MapPaster {
    private val dataCache = mutableMapOf<String, BlockData?>()

    private val materialAliases = mapOf(
        "brushable_block" to Material.SUSPICIOUS_SAND,
        "structure_void" to Material.AIR,
    )

    fun paste(schematic: Schematic, world: World, origin: BlockPos): MapBounds {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE

        val blocks = schematic.blocks
        val palette = schematic.palette
        val area = schematic.width * schematic.length
        val originX = origin.x + schematic.offsetX
        val originY = origin.y + schematic.offsetY
        val originZ = origin.z + schematic.offsetZ

        // Chunk-aware pasting: every chunk the schematic touches is loaded up front (forcing the
        // load once, synchronously, in one pass) and blocks are then set through chunk-relative
        // lookups. Without this, each setBlockData() would go through world.getBlockAt(), which
        // resolves and can load the chunk on every single block - thousands of redundant chunk
        // lookups for larger schematics.
        val minCX = originX shr 4
        val minCZ = originZ shr 4
        val maxCX = (originX + schematic.width - 1) shr 4
        val maxCZ = (originZ + schematic.length - 1) shr 4
        for (cx in minCX..maxCX) {
            for (cz in minCZ..maxCZ) {
                world.getChunkAt(cx, cz)
            }
        }

        var index = 0
        while (index < blocks.size) {
            val paletteIndex = blocks[index]
            if (paletteIndex in palette.indices) {
                val state = palette[paletteIndex]
                if (state.isNotEmpty()) {
                    val data = dataCache.getOrPut(state) {
                        if (dataCache.size > 4096) dataCache.clear() // Bounded: distinct states only.
                        runCatching { toBlockData(state) }.getOrNull()
                    }
                    if (data != null) {
                        val x = index % schematic.width
                        val z = (index / schematic.width) % schematic.length
                        val y = index / area
                        val wx = originX + x
                        val wy = originY + y
                        val wz = originZ + z
                        // getBlockAt on a loaded chunk is cheap; the chunk was forced above.
                        world.getBlockAt(wx, wy, wz).setBlockData(data, false)
                        minX = min(minX, wx); minY = min(minY, wy); minZ = min(minZ, wz)
                        maxX = max(maxX, wx); maxY = max(maxY, wy); maxZ = max(maxZ, wz)
                    }
                }
            }
            index++
        }

        if (minX == Int.MAX_VALUE)
            return MapBounds(origin.x, origin.y, origin.z, origin.x, origin.y, origin.z)

        return MapBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun toBlockData(state: String): BlockData? {
        // Full state strings ("minecraft:oak_stairs[facing=east,half=top]") keep their properties.
        runCatching { return Bukkit.createBlockData(state) }.getOrNull()

        val name = state.substringBefore('[').removePrefix("minecraft:").lowercase()
        if (name in airAliases) return null

        val material = materialAliases[name] ?: Material.matchMaterial(name) ?: return null
        if (!material.isBlock) return null
        return runCatching { material.createBlockData() }.getOrNull()
    }

    private val airAliases = setOf("air", "cave_air", "void_air")
}