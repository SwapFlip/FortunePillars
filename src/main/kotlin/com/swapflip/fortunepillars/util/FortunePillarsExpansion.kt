package com.swapflip.fortunepillars.util

import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.game.util.GameManager
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class FortunePillarsExpansion : PlaceholderExpansion() {
    override fun getIdentifier(): String = "fp"

    override fun getAuthor(): String = "SwapFlip"

    override fun getVersion(): String = FortunePillars.VERSION

    override fun getRequiredPlugin(): String? = "FortunePillars"

    private val stats = setOf("wins", "losses", "kills", "deaths", "games", "streak")
    private val topStats = setOf("wins", "losses", "kills", "deaths", "games", "streak")

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        val p = params.lowercase()
        return when {
            // Players currently in a live game (summed across all running games).
            p == "playing" -> GameManager.games.values
                .sumOf { game -> game.initialPlayers.distinctBy { it.uuid() }.size }
                .toString()

            // Personal stats for the viewing player (%fp_wins%, %fp_kills%, ...).
            p in stats -> {
                if (player == null) return ""
                val d = PlayerStats.get(player.uniqueId)
                when (p) {
                    "wins" -> d.wins
                    "losses" -> d.losses
                    "kills" -> d.kills
                    "deaths" -> d.deaths
                    "games" -> d.gamesPlayed
                    "streak" -> d.bestStreak
                    else -> 0
                }.toString()
            }

            // The player's LuckPerms primary group, if LuckPerms is installed.
            p == "rank" -> if (player == null) "" else Hooks.rankName(player.uniqueId).ifBlank { "" }

            // Leaderboard boards: %fp_top_wins_name_1% / %fp_top_wins_value_1% (index 1-based).
            p.startsWith("top_") -> {
                val rest = p.removePrefix("top_").split("_")
                if (rest.size != 3 || rest[1] !in setOf("name", "value")) return null
                val stat = rest[0]
                val idx = rest[2].toIntOrNull()?.minus(1) ?: return null
                if (stat !in topStats) return null
                val entry = PlayerStats.top(stat, Configuration.leaderboardSize).getOrNull(idx) ?: return ""
                if (rest[1] == "name") Bukkit.getOfflinePlayer(entry.first).name ?: entry.first.toString()
                else entry.second.toString()
            }

            else -> null
        }
    }
}
