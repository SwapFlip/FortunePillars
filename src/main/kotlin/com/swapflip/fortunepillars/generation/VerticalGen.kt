package com.swapflip.fortunepillars.generation

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.util.Configuration

abstract class VerticalGen(val game: Game) {
    abstract fun generate(x: Double, z: Double)

    protected fun execPlace(x: Double, y: Double, z: Double) {
        game.buildings.placeBlock(x, y, z, Configuration.platformMaterial)
    }
}

interface VertGenCompanion<T : VerticalGen> {
    val namespace: String

    fun constructGen(game: Game): T
}
