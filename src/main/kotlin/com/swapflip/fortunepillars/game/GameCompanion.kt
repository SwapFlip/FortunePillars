package com.swapflip.fortunepillars.game

import com.swapflip.fortunepillars.game.util.GameInfo
import org.bukkit.Location
import org.bukkit.entity.Player

interface GameCompanion<T : Game> {
    val gameInfo: GameInfo

    fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): T
}
