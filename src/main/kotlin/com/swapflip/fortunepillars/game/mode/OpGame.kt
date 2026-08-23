package com.swapflip.fortunepillars.game.mode

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameCompanion
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.util.GameInfo
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

class OpGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<OpGame> {
        // The highest gear chance of all modes (LootWeights.op): diamond and netherite gear,
        // tridents, enchanted golden apples, totems, elytra - everything that can end a fight in
        // one drop. Blocks still make up the largest single share of every drop, and the default
        // global blacklist keeps operator-only items and boss eggs (wither, elder guardian,
        // warden) out of every mode, including this one.
        override val gameInfo: GameInfo by lazy { GameInfo(this, "op", lootWeights = { LootWeights.op }) }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): OpGame {
            return OpGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo
}
