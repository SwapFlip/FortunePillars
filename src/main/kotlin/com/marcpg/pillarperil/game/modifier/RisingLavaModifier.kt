package com.marcpg.pillarperil.game.modifier

import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.GameModifierCompanion
import com.marcpg.pillarperil.game.util.GameModifierInfo
import com.marcpg.pillarperil.map.BlockPos
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.Ticking
import org.bukkit.Material

class RisingLavaModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<RisingLavaModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "lava-rises") }

        override fun constructModifier(game: Game): RisingLavaModifier = RisingLavaModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo

    private val intervalSecs = Configuration.provider.getInt("modifiers.lava-rises.interval", 5)
    private val startY = Configuration.provider.getInt("modifiers.lava-rises.start-y", 32)

    private var lavaY: Int = startY
    private val placed = mutableListOf<BlockPos>()

    override fun init() {
        lavaY = startY - 1
        placed.clear()
    }

    override fun tick(tick: Ticking.Tick) {
        val bounds = game.arenaBounds ?: return
        if (!tick.isInInterval(game.startingTick, intervalSecs * 20)) return

        lavaY++
        if (lavaY > bounds.maxY) lavaY = bounds.maxY

        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                val block = game.world.getBlockAt(x, lavaY, z)
                block.setBlockData(Material.LAVA.createBlockData(), false)
                placed += BlockPos(x, lavaY, z)
            }
        }
    }

    override fun onEnd() {
        placed.forEach { game.world.getBlockAt(it.x, it.y, it.z).type = Material.AIR }
        placed.clear()
    }
}
