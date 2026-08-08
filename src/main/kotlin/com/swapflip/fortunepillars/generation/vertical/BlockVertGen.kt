package com.swapflip.fortunepillars.generation.vertical

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.generation.VertGenCompanion
import com.swapflip.fortunepillars.generation.VerticalGen
import com.swapflip.fortunepillars.util.Configuration

class BlockVertGen(game: Game) : VerticalGen(game) {
    companion object : VertGenCompanion<BlockVertGen> {
        override val namespace: String = "block"

        override fun constructGen(game: Game): BlockVertGen = BlockVertGen(game)
    }

    override fun generate(x: Double, z: Double) = execPlace(x, Configuration.platformHeight, z)
}
