package com.swapflip.fortunepillars.util

import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.game.util.GameManager
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

class FortunePillarsExpansion : PlaceholderExpansion() {
    override fun getIdentifier(): String = "fp"

    override fun getAuthor(): String = "SwapFlip"

    override fun getVersion(): String = FortunePillars.VERSION

    override fun getRequiredPlugin(): String? = "FortunePillars"

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        return when (params.lowercase()) {
            "playing" -> GameManager.games.values
                .flatMap { it.initialPlayers }
                .map { it.player.name }
                .distinct()
                .joinToString(", ")
                .ifEmpty { "0" }
            else -> null
        }
    }
}
