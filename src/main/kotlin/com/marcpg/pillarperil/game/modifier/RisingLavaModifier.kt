package com.marcpg.pillarperil.game.modifier

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.GameModifierCompanion
import com.marcpg.pillarperil.game.util.GameModifierInfo
import com.marcpg.pillarperil.map.BlockPos
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.Ticking
import com.marcpg.pillarperil.util.playSoundSafe
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound

class RisingLavaModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<RisingLavaModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "lava-rises") }

        override fun constructModifier(game: Game): RisingLavaModifier = RisingLavaModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo

    private val intervalSecs = Configuration.provider.getInt("modifiers.lava-rises.interval", 5)
    private val startDelaySecs = Configuration.provider.getInt("modifiers.lava-rises.start-delay", 60)
    private val startY = Configuration.provider.getInt("modifiers.lava-rises.start-y", 30)
    private val size = Configuration.provider.getInt("modifiers.lava-rises.size", 50)

    private var lavaY: Int = 0
    private var hasWarned = false
    private val placed = mutableListOf<BlockPos>()

    override fun init() {
        // The lava rises from a fixed height below the map, covering the play area that is defined
        // by the spectator spawn. The pasted arena bounds are never used, since they would flood
        // the player spawns at the top of the arena.
        lavaY = startY - 1
        hasWarned = false
        placed.clear()
    }

    override fun tick(tick: Ticking.Tick) {
        val playArea = game.playArea(size) ?: return
        // Wait for the configured start delay before the lava begins to rise.
        if (!tick.isInInterval(game.startingTick + startDelaySecs * 20, intervalSecs * 20)) return

        lavaY++
        // Never rise above the world's height limit - placing blocks there would throw.
        if (lavaY >= game.world.maxHeight) lavaY = game.world.maxHeight - 1

        for (x in playArea.minX..playArea.maxX) {
            for (z in playArea.minZ..playArea.maxZ) {
                val block = game.world.getBlockAt(x, lavaY, z)
                block.setBlockData(Material.LAVA.createBlockData(), false)
                placed += BlockPos(x, lavaY, z)
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

    override fun onEnd() {
        val area = game.playArea(size)
        val margin = 16
        val top = (placed.maxOfOrNull { it.y } ?: 0).coerceAtLeast(area.maxY + 2)
        val minX = area.minX - margin
        val maxX = area.maxX + margin
        val minZ = area.minZ - margin
        val maxZ = area.maxZ + margin

        // Wipe every lava block in and around the play area - including lava that flowed out of the
        // placed layers into gaps or fell below the arena - so the map is clean for the next game.
        // The region is read through chunk snapshots, which is far cheaper than per-block lookups.
        for (cx in (minX shr 4)..(maxX shr 4)) {
            for (cz in (minZ shr 4)..(maxZ shr 4)) {
                val chunk = game.world.getChunkAt(cx, cz)
                val snapshot = chunk.getChunkSnapshot()
                val baseX = cx * 16
                val baseZ = cz * 16
                for (x in 0..15) {
                    for (z in 0..15) {
                        val wx = baseX + x
                        val wz = baseZ + z
                        if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue
                        for (y in game.world.minHeight..top) {
                            if (snapshot.getBlockType(x, y, z) == Material.LAVA)
                                game.world.getBlockAt(wx, y, wz).type = Material.AIR
                        }
                    }
                }
            }
        }
        placed.clear()
    }
}
