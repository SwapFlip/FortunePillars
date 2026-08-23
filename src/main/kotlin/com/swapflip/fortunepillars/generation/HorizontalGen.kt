package com.swapflip.fortunepillars.generation

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.util.Configuration
import org.bukkit.Location

abstract class HorizontalGen(val game: Game) {
    abstract fun generate(): List<Location>

    protected fun location(x: Double, z: Double): Location = Location(game.world, x, Configuration.platformHeight, z)
}

interface HorGenCompanion<T : HorizontalGen> {
    val namespace: String

    fun constructGen(game: Game): T
}
