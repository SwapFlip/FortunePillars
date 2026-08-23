package com.swapflip.fortunepillars.game.modifier

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.ModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.map.BlockPos
import com.swapflip.fortunepillars.player.PillarPlayer
import com.swapflip.fortunepillars.util.ModifierConfigs
import com.swapflip.fortunepillars.util.Ticking
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData

// Lava Floor: standing on a block heats it up. After `stand-time` seconds of someone standing on
// it, the block ignites into yellow wool, then cooks one stage further every `stage-time` seconds:
// yellow -> orange -> red -> lava. The heat clock starts on the first stand and keeps running even
// if everyone steps off - once a block is heating, it will burn through all stages on its own.
class LavaFloorModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<LavaFloorModifier>("lava-floor", ::LavaFloorModifier)

    override val info: GameModifierInfo = modifierInfo

    private val startDelaySecs = ModifierConfigs.int("lava-floor", "start-delay", 10)
    // Seconds of standing before a block ignites into the first warning stage, and the seconds
    // between each further stage (yellow -> orange -> red -> lava).
    private val standSecs = ModifierConfigs.int("lava-floor", "stand-time", 5)
    private val stageSecs = ModifierConfigs.int("lava-floor", "stage-time", 3)

    private val stageBlocks = listOf(Material.YELLOW_WOOL, Material.ORANGE_WOOL, Material.RED_WOOL, Material.LAVA)

    // Tick thresholds per stage: stage i applies once the block has been heating for
    // standSecs + i * stageSecs. Derived once so the tick loop only compares integers.
    private val stageThresholds = IntArray(stageBlocks.size) { i -> (standSecs + i * stageSecs) * 20 }

    // Position -> tick the block's heat clock started (first time someone stood on it).
    private val heatStart = mutableMapOf<BlockPos, Int>()
    // Position -> last stage applied, so transitions are idempotent and only fire once.
    private val appliedStage = mutableMapOf<BlockPos, Int>()

    // Position -> original block data, captured at the first transform so the lava/wool stages
    // can be undone on game end (the arena wipe does not cover blocks this modifier placed).
    private val originalBlocks = mutableMapOf<BlockPos, BlockData>()

    // Positions this modifier transformed, exposed so other block-wiping teardown (RisingLava's
    // lava wipe) can leave them alone: without this, teardown order decides whether the floor's
    // blocks survive RisingLava's cleanup.
    val ownedPositions: Set<BlockPos> get() = originalBlocks.keys

    override fun init() {
        heatStart.clear()
        appliedStage.clear()
        originalBlocks.clear()
    }

    override fun tick(tick: Ticking.Tick) {
        val now = Bukkit.getCurrentTick()

        // Heat recording: any grounded player heats the solid block under their feet. Air, fluids,
        // the barrier wall and blocks that already finished (lava) never start a clock. Only runs
        // after the configured start delay.
        if (tick.isInInterval(game.anchorTick() + startDelaySecs * 20, 1)) {
            game.players.forEach { p ->
                if (!p.player.isOnline) return@forEach
                val feet = p.player.location
                val pos = BlockPos(feet.blockX, feet.blockY - 1, feet.blockZ)
                if (heatStart.containsKey(pos)) return@forEach
                val type = game.world.getBlockAt(pos.x, pos.y, pos.z).type
                if (type.isAir || type == Material.WATER || type == Material.LAVA || type == Material.BARRIER) return@forEach
                heatStart[pos] = now
            }
        }

        // Stage advancement is purely time-based from each block's heat start: it keeps cooking
        // even when nobody stands on it anymore.
        for ((pos, startedAt) in heatStart) {
            val elapsed = now - startedAt

            var target = -1
            for (i in stageThresholds.indices)
                if (elapsed >= stageThresholds[i]) target = i
            if (target < 0) continue

            val current = appliedStage[pos] ?: -1
            if (target <= current) continue

            val block = game.world.getBlockAt(pos.x, pos.y, pos.z)
            // A player-placed or already-changed block stops the cooking: only blocks still in
            // their previous stage state transform further.
            val expectedType = if (current >= 0) stageBlocks[current] else null
            if (expectedType != null && block.type != expectedType) {
                heatStart.remove(pos)
                continue
            }

            if (pos !in originalBlocks)
                originalBlocks[pos] = block.blockData
            block.setBlockData(stageBlocks[target].createBlockData(), false)
            appliedStage[pos] = target

            if (stageBlocks[target] == Material.LAVA)
                game.players.forEach { p ->
                    if (p.player.isOnline && p.player.location.blockX == pos.x && p.player.location.blockZ == pos.z &&
                        p.player.location.blockY - 1 == pos.y
                    )
                        p.sendActionBar(p.locale().component("modifier.lava-floor.lava", color = NamedTextColor.RED))
                }
        }
    }

    override fun onPlayerDeath(player: PillarPlayer) {}

    override fun onEnd() {
        // Only restore blocks that are still a stage block: the arena reset already restored
        // every position the players placed or broke, and overwriting those with the captured
        // state here would keep a player-placed block (captured as "original" when the floor
        // transformed it) alive through every future reset.
        originalBlocks.forEach { (pos, data) ->
            val block = game.world.getBlockAt(pos.x, pos.y, pos.z)
            if (block.type in stageBlocks)
                block.setBlockData(data, false)
        }
        originalBlocks.clear()
        heatStart.clear()
        appliedStage.clear()
    }
}
