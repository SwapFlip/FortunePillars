package com.marcpg.pillarperil.game.mode

import com.marcpg.libpg.data.time.Time
import com.marcpg.libpg.display.location
import com.marcpg.libpg.display.teleport
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameCompanion
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.util.GameInfo
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.entity.Player

class SwapperGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<SwapperGame> {
        override val gameInfo: GameInfo by lazy { GameInfo(this, "swapper") }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): SwapperGame {
            return SwapperGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo

    init {
        addTickEvent(Time(25, Time.Unit.SECONDS)) {
            val alive = players.toList()
            repeat(5) { i ->
                val remaining = 5 - i
                bukkitRunLater(i * 20L) {
                    if (gameEnded()) return@bukkitRunLater
                    alive.forEach { p ->
                        if (p.player.isOnline)
                            p.player.sendActionBar(component("Swapping in $remaining...", NamedTextColor.GOLD))
                    }
                }
            }
        }

        addTickEvent(Time(30, Time.Unit.SECONDS)) {
            val alive = players.shuffled()
            if (alive.size < 2) return@addTickEvent

            alive.forEach { p ->
                if (p.player.isOnline)
                    p.player.sendActionBar(component("Swapping!", NamedTextColor.GREEN))
            }

            val temp: Location = alive.first().location().clone()
            for (i in 0..<alive.size - 1)
                alive[i].teleport(alive[i + 1].location())
            alive.last().teleport(temp)
        }
    }

    private fun gameEnded() = ending || players.isEmpty()
}
