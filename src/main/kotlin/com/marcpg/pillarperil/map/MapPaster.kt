package com.marcpg.pillarperil.map

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
        var index = 0

        while (index < blocks.size) {
            val paletteIndex = blocks[index]
            if (paletteIndex in palette.indices) {
                val state = palette[paletteIndex]
                if (state.isNotEmpty()) {
                    val data = dataCache.getOrPut(state) { runCatching { toBlockData(state) }.getOrNull() }
                    if (data != null) {
                        val x = index % schematic.width
                        val z = (index / schematic.width) % schematic.length
                        val y = index / area
                        val wx = origin.x + x + schematic.offsetX
                        val wy = origin.y + y + schematic.offsetY
                        val wz = origin.z + z + schematic.offsetZ
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
        val name = state.substringBefore('[').removePrefix("minecraft:").lowercase()
        if (name in airAliases) return null

        val material = materialAliases[name] ?: Material.matchMaterial(name) ?: return null
        if (!material.isBlock) return null
        return runCatching { material.createBlockData() }.getOrNull()
    }

    private val airAliases = setOf("air", "cave_air", "void_air")
}
