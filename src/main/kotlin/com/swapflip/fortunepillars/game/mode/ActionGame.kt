package com.swapflip.fortunepillars.game.mode

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameCompanion
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.util.GameInfo
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

class ActionGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<ActionGame> {
        // A dense mix of everything (LootWeights.action): building blocks to bridge and wall,
        // more weapons and armor than the normal mode, food and utility to survive. Combined with
        // high power-up odds, every cycle keeps the fight moving.
        override val gameInfo: GameInfo by lazy { GameInfo(
            this, "action",
            lootWeights = { LootWeights.action },
            // Every drop cycle hands out exactly one item - like every other mode.
            dropCount = { 1 },
            // Power-ups and special items drop three times as often as in the normal mode.
            powerUpChance = { 30 },
        ) }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): ActionGame {
            return ActionGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo
}
