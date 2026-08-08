package com.swapflip.fortunepillars.game.mode

import com.marcpg.libpg.data.time.Time
import com.marcpg.libpg.display.location
import com.marcpg.libpg.display.teleport
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameCompanion
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.util.GameInfo
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
        // Countdown and swap are driven by the same event, so the warning always matches the swap
        // instead of two independently-clocked intervals drifting apart over time.
        addTickEvent(Time(30, Time.Unit.SECONDS)) {
            // Live list, not a snapshot: eliminated players must not keep receiving warning
            // action bars (or block the swap size check) after they became spectators.
            if (players.size < 2) return@addTickEvent

            repeat(5) { i ->
                val remaining = 5 - i
                bukkitRunLater(i * 20L) {
                    if (gameEnded()) return@bukkitRunLater
                    players.forEach { p ->
                        if (p.player.isOnline)
                            p.player.sendActionBar(p.locale().component("game.swapper.countdown", remaining.toString(), color = NamedTextColor.GOLD))
                    }
                }
            }

            bukkitRunLater(5 * 20L) {
                if (gameEnded() || players.size < 2) return@bukkitRunLater
                val alive = players.shuffled()
                alive.forEach { p ->
                    if (p.player.isOnline)
                        p.player.sendActionBar(p.locale().component("game.swapper.now", color = NamedTextColor.GREEN))
                }

                val temp: Location = alive.first().location().clone()
                for (i in 0..<alive.size - 1)
                    alive[i].teleport(alive[i + 1].location())
                alive.last().teleport(temp)

                // Teleporting grants 3 seconds of invulnerability; clear it so the swap doesn't
                // make players unhittable and swallow hits.
                alive.forEach { p -> p.player.noDamageTicks = 0 }
            }
        }
    }

    private fun gameEnded() = ending || players.isEmpty()
}
