package com.swapflip.fortunepillars.game.util

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.player.PillarPlayer
import com.swapflip.fortunepillars.util.Metrics
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

object GameManager {
    val games = mutableMapOf<String, Game>()

    /*
     * Suggestions for further Metrics:
     *
     * - Death Causes
     * - Player Death Times
     * - Items:
     *   - Items Received:Dropped Ratio
     *   - Item Types Dropped
     */
    val gamesStartedSinceLastFlush = mutableListOf<String>()
    val modifiersUsedSinceLastFlush = mutableListOf<String>()
    val playersPerGameSinceLastFlush = mutableListOf<Int>()

    fun add(game: Game) {
        games[game.id] = game
        // Remember the game world forever: players who reconnect after this game ended are
        // detected by the rejoin handler and sent home instead of being stranded in the world.
        Cage.registerPluginWorld(game.world.name)

        // Only record metrics while FastStats is actually collecting: when it is disabled the
        // flush callback never runs, so an unguarded add() would leak entries forever.
        if (Metrics.isActive()) {
            gamesStartedSinceLastFlush.add(game.info.namespace)
            modifiersUsedSinceLastFlush.addAll(game.modifiers.map { it.info.namespace })
            playersPerGameSinceLastFlush.add(game.initialPlayers.size)
        }
    }

    fun remove(game: Game) {
        games.remove(game.id)
    }

    operator fun get(id: String): Game? = games[id]

    fun player(player: Player, onlyAlive: Boolean = true): PillarPlayer? =
        games.firstNotNullOfOrNull { it.value.player(player, onlyAlive) }

    fun isInGame(player: Player, onlyAlive: Boolean = true): Boolean =
        games.any { it.value.player(player, onlyAlive) != null }

    fun isPartOfGame(entity: Entity): Boolean {
        return if (entity is Player) {
            // onlyAlive=false: eliminated players are still part of the game - their entities
            // (e.g. portal travel) must be handled like any other participant's.
            isInGame(entity, onlyAlive = false)
        } else {
            games.any { entity in it.value.buildings.spawnedEntities }
        }
    }

    fun isWithinGame(location: Location): Boolean {
        val world = location.world
        return games.any { it.value.world == world && it.value.isWithin(location) }
    }

    fun getClosestGame(location: Location, withinBounds: Boolean = true): Game? {
        val world = location.world
        var closest: Game? = null
        var closestDist = Double.MAX_VALUE
        for (game in games.values) {
            if (game.world != world || (withinBounds && !game.isWithin(location))) continue
            val dist = game.center.distanceSquared(location)
            if (dist < closestDist) {
                closest = game
                closestDist = dist
            }
        }
        return closest
    }
}
