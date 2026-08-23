package com.swapflip.fortunepillars.player

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID

// Players viewing a map via /pof spectate: teleported to the map's spectator spot in spectator
// mode, with their pre-spectate game mode restored when they leave. The registry also exists so
// the stranded-player safety net never yanks an active map viewer back to the lobby.
object SpectatorManager {
    private val viewers = mutableMapOf<UUID, GameMode>()

    fun isSpectating(player: Player): Boolean = player.uniqueId in viewers

    // Teleports first, then registers and switches game mode: the teleport itself may fire a
    // world-change event (which calls stop()), so the player must not be registered yet - and a
    // failed teleport must not leave them marked as spectating.
    fun start(player: Player, location: Location): Boolean {
        val previous = player.gameMode
        if (!player.teleport(location)) return false
        viewers[player.uniqueId] = previous
        player.gameMode = GameMode.SPECTATOR
        return true
    }

    fun stop(player: Player) {
        val previous = viewers.remove(player.uniqueId) ?: return
        if (player.isOnline)
            player.gameMode = previous
    }
}