package com.swapflip.fortunepillars.game.mode

import com.marcpg.libpg.display.location
import com.marcpg.libpg.display.teleport
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameCompanion
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.util.GameInfo
import org.bukkit.Location
import org.bukkit.entity.Player

class PlayerShuffleGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<PlayerShuffleGame> {
        override val gameInfo: GameInfo by lazy { GameInfo(this, "player-shuffle") }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): PlayerShuffleGame {
            return PlayerShuffleGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo

    init {
        addItemEvent {
            val players = players.shuffled()

            if (players.isEmpty()) return@addItemEvent

            val temp: Location = players.first().location().clone()
            for (i in 0..<players.size - 1) {
                players[i].teleport(players[i + 1].location())
            }
            players.last().teleport(temp)

            // Clear the invulnerability that teleporting grants, so shuffled players can be hit
            // again immediately.
            players.forEach { p -> p.player.noDamageTicks = 0 }
        }
    }
}
