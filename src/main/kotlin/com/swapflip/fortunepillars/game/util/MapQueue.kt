package com.swapflip.fortunepillars.game.util

import com.swapflip.fortunepillars.map.ArenaMap
import com.swapflip.fortunepillars.player.PlayerSnapshot
import org.bukkit.entity.Player
import java.util.UUID

// Per-map queue state. One instance exists per map that currently has at least one waiting player.
class MapQueue(
    val map: ArenaMap,
    val players: MutableList<Player> = mutableListOf(),
    val votes: MutableMap<UUID, QueueManager.Vote> = mutableMapOf(),
    val snapshots: MutableMap<UUID, PlayerSnapshot> = mutableMapOf(),
    var countdownStart: Long = 0L,
    var countdownDelay: Int = 0,
    var startFailed: Boolean = false,
)
