package com.swapflip.fortunepillars.util

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.util.playSoundSafe
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player

// Lightweight achievement system: a fixed set of accomplishments, each tied to a locale name/desc
// and (optionally) a cosmetic it unlocks. Granting is idempotent - the first time a player earns one
// it is stored on their stats and announced to the whole server.
object Achievements {
    data class Definition(val id: String, val nameKey: String, val descKey: String, val unlocks: String? = null)

    val DEFINITIONS = listOf(
        Definition("first_win", "achievement.first_win.name", "achievement.first_win.desc", "flame"),
        Definition("rampage", "achievement.rampage.name", "achievement.rampage.desc", "note"),
        Definition("killstreak", "achievement.killstreak.name", "achievement.killstreak.desc", "soul"),
        Definition("veteran", "achievement.veteran.name", "achievement.veteran.desc", "heart"),
    )

    private val byId = DEFINITIONS.associateBy { it.id }

    // Grants an achievement to a player. Returns the definition when it was newly earned (so callers
    // can celebrate), or null when already owned / unknown.
    fun grant(player: Player, id: String): Definition? {
        val def = byId[id] ?: return null
        val newlyEarned = PlayerStats.grantAchievement(player.uniqueId, id)
        if (!newlyEarned) return null
        if (def.unlocks != null) PlayerStats.unlockCosmetic(player.uniqueId, def.unlocks)
        celebrate(player, def)
        return def
    }

    private fun celebrate(player: Player, def: Definition) {
        val name = player.locale().string(def.nameKey)
        // Positional args (placeholders) come before the named `color`.
        player.sendMessage(player.locale().component("achievement.unlocked", name, color = NamedTextColor.GOLD))
        player.playSoundSafe(Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        // A short server-wide announce so achievements feel like a moment.
        Bukkit.getOnlinePlayers().forEach { p ->
            if (p != player) p.sendMessage(p.locale().component("achievement.announce", player.name, name, color = NamedTextColor.YELLOW))
        }
    }
}
