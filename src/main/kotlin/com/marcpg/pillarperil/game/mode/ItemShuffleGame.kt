package com.marcpg.pillarperil.game.mode

import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameCompanion
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.util.GameInfo
import com.marcpg.pillarperil.player.PillarPlayer
import org.bukkit.Location
import org.bukkit.entity.Player

class ItemShuffleGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<ItemShuffleGame> {
        override val gameInfo: GameInfo by lazy { GameInfo(this, "item-shuffle") }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): ItemShuffleGame {
            return ItemShuffleGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo

    init {
        addItemEvent {
            players.forEach { p ->
                // Anything a player placed into their offhand survives the shuffle instead of being
                // wiped with the hotbar when the inventory gets cleared.
                p.clearKeepOffhand()
                p.giveItems(items, differentItems = 9)
            }
        }
    }

    // The shuffle give is handled entirely by the item event above; the default give would
    // hand out a 10th item on top of the 9 shuffled ones.
    override fun addItem(player: PillarPlayer) {}
}
