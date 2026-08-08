package com.swapflip.fortunepillars.game.mode

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameCompanion
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.util.GameInfo
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Registry
import org.bukkit.entity.Player

class ChaosGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<ChaosGame> {
        override val gameInfo: GameInfo by lazy { GameInfo(this, "chaos") }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): ChaosGame {
            return ChaosGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo

    // Every material in the game is possible, regardless of the configured item pool.
    // (Overridden here instead of in the constructor, since Game.init() rebuilds the item list.)
    override fun buildItems(enabledCheck: (Material) -> Boolean): List<Material> = Registry.MATERIAL.toList()
}
