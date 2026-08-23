package com.swapflip.fortunepillars.game

import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.map.BlockPos
import com.swapflip.fortunepillars.map.MapBounds
import com.swapflip.fortunepillars.util.Configuration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.scheduler.BukkitTask
import kotlin.math.floor
import kotlin.math.sqrt

// An invisible barrier around the play area that spawns when the game starts, so nobody can run
// out of bounds. Like the lava layers, it is placed in small batches per tick instead of all at
// once, and removed in batches after the game to keep both ends lag-free.
//
// On pasted arenas the barrier follows the arena's rectangular bounds (so corner players are not
// eliminated as "out of bounds"); on generated arenas it is a cylinder of the configured radius.
class GameBorder(
    private val game: Game,
    anchor: Location,
) {
    private val world = game.world
    private val rectBounds: MapBounds? = game.arenaBounds

    // How far past the wall a player can be before the containment check eliminates them: a small
    // margin so players standing against the barrier (or ping-fuzzed inside it) aren't punished.
    private val containmentMargin = 2

    // How many blocks the world has shrunk inward (set by the shrinking-world modifier). The
    // containment check and the red-boundary particles both follow this, so players past the
    // shrunken edge are eliminated exactly like any other out-of-bounds escapee.
    var shrinkBlocks = 0

    // Circle mode (generated arenas): anchored on the spectator-spawn column.
    private val centerX = anchor.blockX
    private val centerZ = anchor.blockZ
    private val radius = Configuration.borderRadius

    // Rectangle mode (pasted arenas): the arena bounds expanded by the containment margin.
    private val minX = (rectBounds?.minX ?: 0) - containmentMargin
    private val maxX = (rectBounds?.maxX ?: 0) + containmentMargin
    private val minZ = (rectBounds?.minZ ?: 0) - containmentMargin
    private val maxZ = (rectBounds?.maxZ ?: 0) + containmentMargin

    private val minY = (anchor.blockY - Configuration.borderBottomOffset)
        // On pasted arenas the wall must reach down to the arena floor: a spectator spawn high up
        // (or a top-offset that ends above the floor) would otherwise leave a walk-under gap
        // between the wall bottom and the map's floor.
        .coerceAtMost((game.arenaBounds?.minY ?: Int.MAX_VALUE) - 1)
        .coerceAtLeast(world.minHeight)
    private val maxY = (anchor.blockY + Configuration.borderTopOffset).coerceAtMost(world.maxHeight - 1)

    // Whether a location has escaped the border: horizontally beyond the wall (plus the margin) or
    // above its top. There is no floor check - below the wall the arena floor catches the fall,
    // and the void handling kills anyone who drops through it.
    fun isOutOfBounds(location: Location): Boolean {
        if (location.blockY > maxY + containmentMargin) return true
        return if (rectBounds != null) {
            location.blockX < minX + shrinkBlocks - containmentMargin || location.blockX > maxX - shrinkBlocks + containmentMargin ||
                location.blockZ < minZ + shrinkBlocks - containmentMargin || location.blockZ > maxZ - shrinkBlocks + containmentMargin
        } else {
            val dx = location.blockX - centerX
            val dz = location.blockZ - centerZ
            val r = radius - shrinkBlocks
            dx * dx + dz * dz > (r + containmentMargin) * (r + containmentMargin)
        }
    }

    // Shrinks the world inward by `blocks`, moving the containment boundary (and the red-particle
    // edge drawn by the shrinking-world modifier) closer to the center.
    fun shrinkBy(blocks: Int) {
        shrinkBlocks += blocks
    }

    // The current (shrunk) deadly boundary, for the red-particle rendering: matches the elimination
    // edge computed in isOutOfBounds.
    sealed interface Boundary
    data class CircleBoundary(val cx: Int, val cz: Int, val radius: Int, val minY: Int, val maxY: Int) : Boundary
    data class RectBoundary(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int, val minY: Int, val maxY: Int) : Boundary

    fun currentBoundary(): Boundary = if (rectBounds != null) {
        RectBoundary(
            minX + shrinkBlocks - containmentMargin, maxX - shrinkBlocks + containmentMargin,
            minZ + shrinkBlocks - containmentMargin, maxZ - shrinkBlocks + containmentMargin,
            minY, maxY,
        )
    } else {
        CircleBoundary(centerX, centerZ, (radius - shrinkBlocks + containmentMargin).coerceAtLeast(0), minY, maxY)
    }

    private val blocksPerTick = 512

    private val barrier: BlockData = Material.BARRIER.createBlockData()
    private val air: BlockData = Material.AIR.createBlockData()

    // Positions that still need to be placed (or removed).
    private val pending = java.util.ArrayDeque<BlockPos>()
    private var drain: (BlockPos) -> Unit = ::place
    private var task: BukkitTask? = null

    fun place() {
        enqueueRing()
        task = Bukkit.getScheduler().runTaskTimer(FortunePillars.PLUGIN, Runnable {
            drainBatch()
            // Self-cancel once the ring is fully placed: the placement task must not tick for the
            // whole match (and beyond) as a no-op. remove() starts its own task when the game ends.
            if (pending.isEmpty())
                task?.cancel()
        }, 1L, 1L)
    }

    fun remove() {
        task?.cancel()
        task = null
        // Re-generate the whole ring and wipe every barrier in it - including leftovers from a
        // previous crash or restart, which this game never tracked itself.
        pending.clear()
        drain = ::remove
        enqueueRing()
        task = Bukkit.getScheduler().runTaskTimer(FortunePillars.PLUGIN, Runnable {
            drainBatch()
            if (pending.isEmpty())
                task?.cancel()
        }, 1L, 1L)
    }

    private fun drainBatch() {
        if (pending.isEmpty()) return
        for (i in 0 until blocksPerTick) {
            val pos = pending.pollFirst() ?: break
            drain(pos)
        }
    }

    private fun place(pos: BlockPos) {
        val block = world.getBlockAt(pos.x, pos.y, pos.z)
        if (block.type == Material.AIR)
            block.setBlockData(barrier, false)
    }

    private fun remove(pos: BlockPos) {
        val block = world.getBlockAt(pos.x, pos.y, pos.z)
        if (block.type == Material.BARRIER)
            block.setBlockData(air, false)
    }

    // All wall positions: a rectangular ring (pasted arenas) or a circular one-block-thick ring
    // (generated arenas), for every y in the wall's height range.
    private fun enqueueRing() {
        if (minY > maxY) return
        if (rectBounds != null) {
            val x0 = minX - containmentMargin
            val x1 = maxX + containmentMargin
            val z0 = minZ - containmentMargin
            val z1 = maxZ + containmentMargin
            for (y in minY..maxY) {
                for (x in x0..x1) {
                    pending.addLast(BlockPos(x, y, z0))
                    pending.addLast(BlockPos(x, y, z1))
                }
                for (z in z0 + 1 until z1) {
                    pending.addLast(BlockPos(x0, y, z))
                    pending.addLast(BlockPos(x1, y, z))
                }
            }
        } else {
            val r = radius
            val inner = (r - 1) * (r - 1)
            val outer = r * r
            for (x in -r..r) {
                val maxZ = floor(sqrt((outer - x * x).toDouble())).toInt()
                for (z in -maxZ..maxZ) {
                    val dist = x * x + z * z
                    if (dist in inner..outer) {
                        for (y in minY..maxY)
                            pending.addLast(BlockPos(centerX + x, y, centerZ + z))
                    }
                }
            }
        }
    }
}
