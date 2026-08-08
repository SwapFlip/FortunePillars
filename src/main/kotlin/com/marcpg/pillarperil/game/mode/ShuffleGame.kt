package com.marcpg.pillarperil.game.mode

import com.marcpg.libpg.data.time.Time
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
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
        // Countdown and shuffle are driven by the same event, so the warning always matches the
        // shuffle instead of two independently-clocked intervals drifting apart over time. Each
        // cycle the previous random items are removed and 10 fresh ones are handed out, so the
        // inventory never accumulates stale items across reshuffles.
        addTickEvent(Time(60, Time.Unit.SECONDS)) {
            // Live list, not a snapshot: eliminated players must not keep receiving warning
            // action bars after they became spectators.
            if (players.isEmpty()) return@addTickEvent

            repeat(10) { i ->
                val remaining = 10 - i
                bukkitRunLater(i * 20L) {
                    if (gameEnded()) return@bukkitRunLater
                    players.forEach { p ->
                        if (p.player.isOnline)
                            p.player.sendActionBar(p.locale().component("game.shuffle.countdown", remaining.toString(), color = NamedTextColor.AQUA))
                    }
                }
            }

            bukkitRunLater(10 * 20L) {
                if (gameEnded() || players.isEmpty()) return@bukkitRunLater
                players.forEach { p ->
                    val pl = p.player
                    // The old random items are removed and 10 fresh ones are handed out; anything put
                    // into the offhand survives the reshuffle.
                    p.clearKeepOffhand()
                    p.giveItems(items, differentItems = 10)
                    if (pl.isOnline)
                        pl.sendActionBar(p.locale().component("game.shuffle.now", color = NamedTextColor.GREEN))
                }
            }
        }
    }

    private fun gameEnded() = ending || players.isEmpty()
}
