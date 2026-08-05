package com.marcpg.pillarperil.game.modifier

import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.GameModifierCompanion
import com.marcpg.pillarperil.game.util.GameModifierInfo
import com.marcpg.pillarperil.map.BlockPos
import com.marcpg.pillarperil.util.Configuration
import org.bukkit.Material

class RisingLavaModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<RisingLavaModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "lava-rises") }

        override fun constructModifier(game: Game): RisingLavaModifier = RisingLavaModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo

    private val step = Configuration.provider.getInt("modifiers.lava-rises.step", 1)
    private val startBelow = Configuration.provider.getInt("modifiers.lava-rises.start-below", 3)

    private var lavaY: Int = 0
    private val placed = mutableListOf<BlockPos>()

    override fun init() {
        lavaY = (game.arenaBounds?.minY ?: 0) - startBelow
    }

    override fun onItemCycle() {
        val bounds = game.arenaBounds ?: return

        lavaY += step
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
