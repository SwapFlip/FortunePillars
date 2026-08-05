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
import net.kyori.adventure.title.Title
import org.bukkit.Material
import org.bukkit.Sound

class RisingLavaModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<RisingLavaModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "lava-rises") }

        override fun constructModifier(game: Game): RisingLavaModifier = RisingLavaModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo

    private val intervalSecs = Configuration.provider.getInt("modifiers.lava-rises.interval", 5)
    private val startY = Configuration.provider.getInt("modifiers.lava-rises.start-y", 32)
    private val startDelaySecs = Configuration.provider.getInt("modifiers.lava-rises.start-delay", 60)

    private var lavaY: Int = startY
    private var hasWarned = false
    private val placed = mutableListOf<BlockPos>()

    override fun init() {
        lavaY = startY - 1
        hasWarned = false
        placed.clear()
    }

    override fun tick(tick: Ticking.Tick) {
        val bounds = game.arenaBounds ?: return
        // Wait for the configured start delay before the lava begins to rise.
        if (!tick.isInInterval(game.startingTick + startDelaySecs * 20, intervalSecs * 20)) return

        lavaY++
        if (lavaY > bounds.maxY) lavaY = bounds.maxY

        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                val block = game.world.getBlockAt(x, lavaY, z)
                block.setBlockData(Material.LAVA.createBlockData(), false)
                placed += BlockPos(x, lavaY, z)
            }
        }

        if (!hasWarned) {
            hasWarned = true
            game.players.forEach { p ->
                p.showTitle(Title.title(
                    p.locale().component("modifier.lava-rises.rising", color = NamedTextColor.RED),
                    p.locale().component("modifier.lava-rises.name", color = NamedTextColor.YELLOW)
                ))
            }
            game.players.playSoundSafe(Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.5f)
        }
    }

    override fun onEnd() {
        placed.forEach { game.world.getBlockAt(it.x, it.y, it.z).type = Material.AIR }
        placed.clear()
    }
}