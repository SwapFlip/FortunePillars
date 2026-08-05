package com.marcpg.pillarperil.map

import org.bukkit.Location
import org.bukkit.World

data class BlockPos(val x: Int, val y: Int, val z: Int) {
    fun toLocation(world: World, yOffset: Double = 0.0) = Location(world, x + 0.5, y + yOffset, z + 0.5)
}

class ArenaMap(
    val name: String,
    val schematic: String,
    val world: String,
    val origin: BlockPos,
    val spawns: MutableList<BlockPos> = mutableListOf(),
    var spectatorSpawn: BlockPos? = null,
    var deathHeight: Int? = null,
) {
    fun originLocation(world: World) = origin.toLocation(world)
    fun spectatorLocation(world: World): Location = spectatorSpawn?.toLocation(world) ?: origin.toLocation(world)
}
