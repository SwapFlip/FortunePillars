package com.swapflip.fortunepillars.game.modifier

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.ModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.map.BlockPos
import com.swapflip.fortunepillars.util.ModifierConfigs
import com.swapflip.fortunepillars.util.Ticking
import com.swapflip.fortunepillars.util.playSoundSafe
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import java.util.ArrayDeque

class RisingLavaModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<RisingLavaModifier>("lava-rises", ::RisingLavaModifier)

    override val info: GameModifierInfo = modifierInfo

    private val startDelaySecs = ModifierConfigs.int("lava-rises", "start-delay", 10)
    private val startY = ModifierConfigs.int("lava-rises", "start-y", 30)
    private val spectatorOffset = ModifierConfigs.int("lava-rises", "spectator-offset", 70)
    private val size = ModifierConfigs.int("lava-rises", "size", 75)
    // Extra seconds on top of the mode's item cycle between two lava rises: 5s modes rise every
    // 7s, 3s modes every 5s, 10s modes every 12s (configurable via `interval-extra`).
    private val intervalExtraSecs = ModifierConfigs.int("lava-rises", "interval-extra", 2)

    // Surface layer placement rate: scaled at init so a full surface (size^2 blocks) is always
    // placed within one rise interval, even in fast item cycles (e.g. Speedrunner), so the
    // pending queue can never outgrow the skip threshold and permanently stall the rise.
    private var surfaceBlocksPerTick = 64
    // The air-fill below the surface gets its own matching budget, so the pit flood keeps pace.
    private var fillBlocksPerTick = 64
    // Columns of the play area, cached at init so the front scan never rebuilds them per tick.
    private val columns = mutableListOf<Pair<Int, Int>>()

    // Top layers waiting to be placed (new surface layers jump the queue via addFirst, so the
    // visible hazard always appears first).
    private val pending = ArrayDeque<BlockPos>()
    // Air blocks below the surface waiting to be filled, appended by the descending front.
    private val fillPending = ArrayDeque<BlockPos>()
    private val lava = Material.LAVA.createBlockData()

    private var lavaY: Int = 0
    // The descending flood front: the layer currently being scanned for air pockets, starting
    // just below the surface and working its way down to the play area floor.
    private var frontY = Int.MIN_VALUE
    private var frontCol = 0
    // How many more layers the front may scan. It is granted exactly one layer per rise (plus the
    // first layer at init), so the pit flood trails the surface instead of racing ahead of it:
    // the whole volume under the lava surface still fills up, just no faster than one layer per
    // rise interval, and players who fall into the pit keep their footing (and their chance to
    // pillar back up) until the flood actually reaches their depth.
    private var frontRemaining = 1
    private var hasWarned = false
    // Original block state of every block the modifier replaced, so onEnd() can restore the map
    // exactly as it was (captured at placement time, i.e. only for lava that actually got placed).
    private val originals = mutableMapOf<BlockPos, org.bukkit.block.data.BlockData>()

    override fun init() {
        // The lava rises from a fixed height below the map, covering the play area that is defined
        // by the spectator spawn. The pasted arena bounds are never used, since they would flood
        // the player spawns at the top of the arena. When the map defines a spectator spawn it is
        // anchored spectatorOffset blocks below it (so the lava tops out just under the spectator
        // platform), otherwise it falls back to the configured start height.
        val spectator = game.map?.spectatorSpawn
        lavaY = (if (spectator != null) spectator.y - spectatorOffset else startY) - 1
        hasWarned = false
        originals.clear()
        pending.clear()
        fillPending.clear()

        // The flood front starts just below the surface and descends layer by layer, turning
        // every air pocket in the pit into lava (solid blocks are left alone). It runs alongside
        // the surface placement, so the whole play area below the lava is eventually one solid
        // flood - but only where there is actually air. It is gated to one layer per rise (see
        // frontRemaining), so it never drowns the pit ahead of the lava level.
        val area = game.playArea(size)
        columns.clear()
        for (x in area.minX..area.maxX)
            for (z in area.minZ..area.maxZ)
                columns += x to z
        frontY = lavaY - 1
        frontCol = 0
        frontRemaining = 1

        // Scale the per-tick placement budget so the enqueued surface (size^2 blocks) and the
        // one-layer-per-rise pit fill are both placed within a single rise interval. This keeps
        // the pending queue bounded and the lava rising at full speed regardless of item-cycle pace.
        val riseTicks = ((game.itemCountdown() + intervalExtraSecs) * 20).toInt().coerceAtLeast(20)
        val budget = ((size * size) / riseTicks + 16).coerceAtLeast(64)
        surfaceBlocksPerTick = budget
        fillBlocksPerTick = budget
    }

    override fun tick(tick: Ticking.Tick) {
        val playArea = game.playArea(size)

        // Place the queued surface blocks in small batches spread over ticks instead of all at once.
        for (i in 0 until surfaceBlocksPerTick) {
            val pos = pending.pollFirst() ?: break
            place(pos)
        }

        // Fill air pockets below the surface: the front scans its current layer in chunks (gated
        // by the fill queue size, so the scan never outruns the placement) and queues every air
        // block it finds. Solid blocks are skipped - only air becomes lava. The front only moves
        // on while it has layers left from the last rise, so the flood trails the lava surface.
        if (fillPending.size < fillBlocksPerTick * 16) {
            var scanned = 0
            while (frontRemaining > 0 && frontY >= playArea.minY && scanned < fillBlocksPerTick * 16) {
                val (x, z) = columns[frontCol]
                val type = game.world.getBlockAt(x, frontY, z).type
                if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR)
                    fillPending.addLast(BlockPos(x, frontY, z))
                scanned++
                frontCol++
                if (frontCol >= columns.size) {
                    frontCol = 0
                    frontY--
                    frontRemaining--
                }
            }
        }
        for (i in 0 until fillBlocksPerTick) {
            val pos = fillPending.pollFirst() ?: break
            place(pos)
        }

        // Wait for the configured start delay before the lava begins to rise. From then on the
        // surface rises one block every item cycle + `interval-extra` seconds (3->5, 5->7, 10->12,
        // 15->17), so the pace of the match always stays in sync with how fast items are dropping.
        if (!tick.isInInterval(game.anchorTick() + startDelaySecs * 20, ((game.itemCountdown() + intervalExtraSecs) * 20).toInt())) return

        lavaY++
        // Rise all the way to the top of the play area (the world height limit is far above the
        // arena and would let the flood bury the player spawns). The top layer is flooded too, so
        // the arena is fully covered when the lava tops out.
        if (lavaY > playArea.maxY) return

        // Every rise lets the pit flood descend exactly one more layer below the new surface.
        frontRemaining++

        // The new surface layer jumps the placement queue, so the rising lava is always visible
        // immediately and the flood below simply grows with it. Only up to one rise-interval of
        // blocks are enqueued per rise, so the pending queue stays bounded and the lava never
        // stalls even in fast item cycles (e.g. Speedrunner).
        val riseTicks = ((game.itemCountdown() + intervalExtraSecs) * 20).toInt().coerceAtLeast(20)
        val budget = surfaceBlocksPerTick * riseTicks
        val width = playArea.maxZ - playArea.minZ + 1
        outer@ for ((i, x) in (playArea.minX..playArea.maxX).withIndex()) {
            for (z in playArea.minZ..playArea.maxZ) {
                pending.addFirst(BlockPos(x, lavaY, z))
                if (i * width >= budget) break@outer
            }
        }

        if (!hasWarned) {
            hasWarned = true
            game.players.forEach { p ->
                p.sendActionBar(p.locale().component("modifier.lava-rises.rising", color = NamedTextColor.RED))
            }
            game.players.playSoundSafe(Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.5f)
        }
    }

    private fun place(pos: BlockPos) {
        val block = game.world.getBlockAt(pos.x, pos.y, pos.z)
        originals.getOrPut(pos) { block.blockData }
        block.setBlockData(lava, false)
    }

    override fun onEnd() {
        val area = game.playArea(size)
        val margin = 16
        val top = (originals.keys.maxOfOrNull { it.y } ?: 0).coerceAtLeast(area.maxY + 2).coerceAtMost(game.world.maxHeight - 1)
        // Lava below the lowest placed layer fell into the void - harmless and unreachable, so the
        // wipe only scans from the shallowest placed layer down to the arena floor.
        val minY = (originals.keys.minOfOrNull { it.y } ?: area.minY).coerceAtLeast(game.world.minHeight).coerceAtMost(area.minY)
        val minX = area.minX - margin
        val maxX = area.maxX + margin
        val minZ = area.minZ - margin
        val maxZ = area.maxZ + margin

        // Positions the Lava Floor modifier transformed: its blocks are its own to restore, so the
        // wipe below must leave them alone. Otherwise teardown order decides whether the floor's
        // lava survives (RisingLava wiping it to air first would punch permanent holes).
        val lavaFloorOwned = game.modifierOf(com.swapflip.fortunepillars.game.modifier.LavaFloorModifier::class.java)?.ownedPositions ?: emptySet()

        // Restore the blocks the lava replaced, but only while they are still lava: blocks the
        // players broke or replaced during the match are left alone. Only loaded chunks are
        // touched, so cleanup never yanks distant chunks into memory.
        val loaded = HashSet<Pair<Int, Int>>()
        for ((pos, original) in originals) {
            if (pos.y < minY || pos.y > top) continue
            val chunkKey = (pos.x shr 4) to (pos.z shr 4)
            if (chunkKey !in loaded) {
                if (!game.world.isChunkLoaded(chunkKey.first, chunkKey.second)) continue
                loaded += chunkKey
            }
            val block = game.world.getBlockAt(pos.x, pos.y, pos.z)
            if (block.type == Material.LAVA) block.blockData = original
        }

        // Wipe every remaining lava block in and around the play area - including lava that flowed
        // out of the placed layers into gaps - so the map is clean for the next game.
        // The region is read through chunk snapshots, which is far cheaper than per-block lookups.
        for (cx in (minX shr 4)..(maxX shr 4)) {
            for (cz in (minZ shr 4)..(maxZ shr 4)) {
                if (!game.world.isChunkLoaded(cx, cz)) continue
                val chunk = game.world.getChunkAt(cx, cz)
                val snapshot = chunk.getChunkSnapshot()
                val baseX = cx * 16
                val baseZ = cz * 16
                for (x in 0..15) {
                    for (z in 0..15) {
                        val wx = baseX + x
                        val wz = baseZ + z
                        if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue
                        for (y in minY..top) {
                            if (BlockPos(wx, y, wz) in lavaFloorOwned) continue
                            if (snapshot.getBlockType(x, y, z) == Material.LAVA)
                                game.world.getBlockAt(wx, y, wz).type = Material.AIR
                        }
                    }
                }
            }
        }
        originals.clear()
    }
}