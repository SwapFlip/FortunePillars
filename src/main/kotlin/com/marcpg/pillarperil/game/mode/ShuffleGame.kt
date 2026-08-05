package com.marcpg.pillarperil.game.mode

import com.marcpg.libpg.data.time.Time
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameCompanion
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.util.GameInfo
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.entity.Player

class ShuffleGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<ShuffleGame> {
        override val gameInfo: GameInfo by lazy { GameInfo(this, "shuffle") }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): ShuffleGame {
            return ShuffleGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo

    init {
        addTickEvent(Time(50, Time.Unit.SECONDS)) {
            val alive = players.toList()
            repeat(10) { i ->
                val remaining = 10 - i
                bukkitRunLater(i * 20L) {
                    if (ending || players.isEmpty()) return@bukkitRunLater
                    alive.forEach { p ->
                        if (p.player.isOnline)
                            p.player.sendActionBar(component("Inventory shuffling in $remaining...", NamedTextColor.AQUA))
                    }
                }
            }
        }

        addTickEvent(Time(60, Time.Unit.SECONDS)) {
            players.forEach { p ->
                val inv = p.player.inventory
                val items = (0 until 36).map { inv.getItem(it) }.shuffled()
                for (slot in 0 until 36)
                    inv.setItem(slot, items[slot])
                if (p.player.isOnline)
                    p.player.sendActionBar(component("Inventory shuffled!", NamedTextColor.GREEN))
            }
        }
    }
}
