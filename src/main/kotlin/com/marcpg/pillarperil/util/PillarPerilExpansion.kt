package com.marcpg.pillarperil.util

import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.game.util.GameManager
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

class PillarPerilExpansion : PlaceholderExpansion() {
    override fun getIdentifier(): String = "pp"

    override fun getAuthor(): String = "MarcPG"

    override fun getVersion(): String = PillarPeril.VERSION

    override fun getRequiredPlugin(): String? = "PillarPeril"

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
