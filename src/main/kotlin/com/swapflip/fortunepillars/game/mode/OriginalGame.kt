package com.swapflip.fortunepillars.game.mode

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameCompanion
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.util.GameInfo
import org.bukkit.Location
import org.bukkit.entity.Player

class OriginalGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<OriginalGame> {
        override val gameInfo: GameInfo by lazy { GameInfo(this, "original") }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): OriginalGame {
            return OriginalGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo
}
