package com.marcpg.pillarperil.game.mode

import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameCompanion
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.util.GameInfo
import org.bukkit.Location
import org.bukkit.entity.Player

class OpGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<OpGame> {
        override val gameInfo: GameInfo by lazy { GameInfo(this, "op") }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): OpGame {
            return OpGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo
}
