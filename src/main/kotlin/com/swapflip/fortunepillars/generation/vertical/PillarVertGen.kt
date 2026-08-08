package com.swapflip.fortunepillars.generation.vertical

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.generation.VertGenCompanion
import com.swapflip.fortunepillars.generation.VerticalGen
import com.swapflip.fortunepillars.util.Configuration

class PillarVertGen(game: Game) : VerticalGen(game) {
    companion object : VertGenCompanion<PillarVertGen> {
        override val namespace: String = "pillar"

        override fun constructGen(game: Game): PillarVertGen = PillarVertGen(game)
    }

    override fun generate(x: Double, z: Double) {
        for (y in game.world.minHeight..Configuration.platformHeight.toInt())
            execPlace(x, y.toDouble(), z)
    }
}
