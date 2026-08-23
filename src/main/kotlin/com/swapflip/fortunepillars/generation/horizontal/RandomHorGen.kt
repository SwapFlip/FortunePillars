package com.swapflip.fortunepillars.generation.horizontal

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.generation.HorGenCompanion
import com.swapflip.fortunepillars.generation.HorizontalGen
import org.bukkit.Location

class RandomHorGen(game: Game) : HorizontalGen(game) {
    companion object : HorGenCompanion<RandomHorGen> {
        override val namespace: String = "random"

        override fun constructGen(game: Game): RandomHorGen = RandomHorGen(game)
    }

    val players = game.players.size

    override fun generate(): List<Location> {
        // Inflate the generation radius locally for this random layout; never mutate the shared
        // game.radius, or the border (built earlier with the original radius) and the play-area used
        // by modifiers during the match would disagree.
        val radius = game.radius * 1.2

        val candidates = mutableSetOf<Location>()
        for (x in -radius.toInt()..<radius.toInt()) {
            for (z in -radius.toInt()..<radius.toInt()) {
                val loc = game.center.clone().add(x.toDouble(), 0.0, z.toDouble())
                if (game.center.distance(loc) <= radius)
                    candidates += loc
            }
        }

        val locations = mutableListOf<Location>()
        (0..<players).forEach { _ ->
            val loc = candidates.random()
            candidates -= loc
            locations += location(loc.x, loc.z)
        }
        return locations
    }
}
