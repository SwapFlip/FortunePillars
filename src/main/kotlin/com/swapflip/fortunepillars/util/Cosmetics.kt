package com.swapflip.fortunepillars.util

import com.swapflip.fortunepillars.FortunePillars
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player

// Cosmetic particle trails: a small, self-contained system (no external lib needed - just the
// native spawnParticle API). Each trail is unlocked by an achievement (see Achievements) and the
// player's active choice is persisted on their stats. A single timer emits the chosen particle
// around every online player who has one selected.
object Cosmetics {
    data class Trail(val id: String, val nameKey: String, val particle: Particle)

    val TRAILS = mapOf(
        "flame" to Trail("flame", "cosmetic.flame", Particle.FLAME),
        "note" to Trail("note", "cosmetic.note", Particle.NOTE),
        "soul" to Trail("soul", "cosmetic.soul", Particle.SOUL),
        "heart" to Trail("heart", "cosmetic.heart", Particle.HEART),
    )

    // Emits the chosen trail particle for each online player. Reads only the in-memory cache (players
    // are warmed into it on join), so this never touches disk on the hot path.
    fun tick() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.gameMode == GameMode.SPECTATOR) continue
            val data = PlayerStats.cached(player.uniqueId) ?: continue
            val id = data.activeCosmetic
            if (id.isBlank() || id == "none") continue
            if (id !in data.cosmetics) continue
            val trail = TRAILS[id] ?: continue
            val loc = player.location
            player.world.spawnParticle(trail.particle, loc.x, loc.y + 1.0, loc.z, 1, 0.15, 0.15, 0.15, 0.0)
        }
    }

    fun startTask() {
        Bukkit.getScheduler().runTaskTimer(FortunePillars.PLUGIN, Runnable { tick() }, 4L, 4L)
    }
}
