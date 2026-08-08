package com.swapflip.fortunepillars.generation.horizontal

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.generation.HorGenCompanion
import com.swapflip.fortunepillars.generation.HorizontalGen
import org.bukkit.Location
import kotlin.math.cos
import kotlin.math.sin

class CircularHorGen(game: Game) : HorizontalGen(game) {
    companion object : HorGenCompanion<CircularHorGen> {
        override val namespace: String = "circular"

        override fun constructGen(game: Game): CircularHorGen = CircularHorGen(game)
    }

    val players = game.players.size

    override fun generate(): List<Location> {
        val locations = mutableListOf<Location>()
        for (i in 0..<players) {
            val angle = Math.TAU * i / players
            locations += location(
                game.center.x + game.radius * cos(angle),
                game.center.z + game.radius * sin(angle)
            )
        }
        return locations
    }
}
