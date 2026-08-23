package com.swapflip.fortunepillars.generation

import com.swapflip.fortunepillars.game.Game
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Waterlogged
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

class Buildings(
    val game: Game,
    val horizontalGen: HorizontalGen,
    val verticalGen: VerticalGen,
) {
    var placedRadius = 0.0

    // Fluid block data carries its level, but the setter still needs physics to make it settle and
    // spread; every other block type is placed directly.
    private fun needsPhysics(data: BlockData): Boolean =
        data.material in FLUIDS || (data as? Waterlogged)?.isWaterlogged == true

    private companion object {
        val FLUIDS = setOf(Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN)
    }

    val initialBlocks = mutableMapOf<Location, BlockData>()
    val spawnedEntities = mutableListOf<Entity>()

    fun placeBlock(x: Number, y: Number, z: Number, type: Material) = placeBlock(Location(game.world, x.toDouble(), y.toDouble(), z.toDouble()), type)

    fun placeBlock(location: Location, type: Material) {
        // A misconfigured platform-height (or a schematic with an extreme offset) must never throw
        // out of the game start: clamp into the world's buildable range instead of crashing init().
        location.setY(location.y.coerceIn(game.world.minHeight.toDouble(), (game.world.maxHeight - 1).toDouble()))
        registerPlace(location)
        location.block.setBlockData(type.createBlockData(), false)
    }

    fun registerPlace(location: Location, data: BlockData = location.block.blockData) {
        if (location !in initialBlocks) {
            initialBlocks[location.clone()] = data

            val distance = location.distance(game.center)
            if (distance > placedRadius)
                placedRadius = distance
        }
    }

    fun registerSpawn(entity: Entity) {
        if (entity !is Player)
            spawnedEntities.add(entity)
    }

    fun generate(): List<Location> {
        val locations = horizontalGen.generate()
        locations.forEach { verticalGen.generate(it.x, it.z) }
        return locations
    }

    fun reset() {
        // A single pass: physics only matter for fluids and waterlogged blocks (so they settle
        // and spread correctly), everything else is set directly.
        initialBlocks.forEach { (l, b) ->
            l.block.setBlockData(b, needsPhysics(b))
        }

        // Kill all entities spawned during this game. Prune already-destroyed ones first, so a long
        // game (TNT, arrows, falling blocks...) never turns this pass into a stale-entity parade.
        spawnedEntities.removeAll { !it.isValid || !it.isInWorld }
        spawnedEntities.forEach { it.remove() }
        spawnedEntities.clear()
    }
}
