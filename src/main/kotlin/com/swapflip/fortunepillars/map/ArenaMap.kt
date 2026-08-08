package com.swapflip.fortunepillars.map

import org.bukkit.Location
import org.bukkit.World

data class BlockPos(val x: Int, val y: Int, val z: Int) {
    fun toLocation(world: World, yOffset: Double = 0.0) = Location(world, x + 0.5, y + yOffset, z + 0.5)
}

class ArenaMap(
    val name: String,
    val schematic: String,
    val world: String,
    var origin: BlockPos,
    val spawns: MutableList<BlockPos> = mutableListOf(),
    var spectatorSpawn: BlockPos? = null,
    var deathHeight: Int? = null,
) {
    // Nice name and description shown in the map selection menu; set in the map's yml file.
    var displayName: String? = null
    var description: String? = null

    fun originLocation(world: World) = origin.toLocation(world)
    fun spectatorLocation(world: World): Location = spectatorSpawn?.toLocation(world) ?: origin.toLocation(world)
}
