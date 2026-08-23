package com.swapflip.fortunepillars.player

import com.swapflip.fortunepillars.util.getAttributeSafe
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.scoreboard.Scoreboard
import java.util.*
import kotlin.math.min

data class PlayerSnapshot(
    val uuid: UUID,
    val displayName: Component,
    val inventory: List<ItemStack?>,
    val exp: Float,
    val totalExperience: Int,
    val level: Int,
    val location: Location,
    val respawnLocation: Location?,
    val gameMode: GameMode,
    val scoreboard: Scoreboard,
    val health: Double,
    val foodLevel: Int,
    val saturation: Float,
    val activeEffects: Collection<PotionEffect> = emptyList(),
    val allowFlight: Boolean = false,
    val isFlying: Boolean = false,
    val maxHealth: Double = 20.0,
) {
    constructor(player: Player) : this(
        player.uniqueId,
        player.displayName(),
        player.inventory.contents.copyOf().toList(),
        player.exp,
        player.totalExperience,
        player.level,
        player.location.clone(),
        player.respawnLocation?.clone(),
        player.gameMode,
        player.scoreboard,
        player.health,
        player.foodLevel,
        player.saturation,
        player.activePotionEffects.toList(),
        player.allowFlight,
        player.isFlying,
        player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0,
    )

    fun set(
        player: Player,
        restoreName: Boolean = false,
        restoreLocation: Boolean = true,
        restoreInventory: Boolean = true,
        restoreExperience: Boolean = true,
        restoreRespawnLocation: Boolean = true,
        restoreGameMode: Boolean = true,
        restoreScoreboard: Boolean = true,
        restoreHealth: Boolean = true,
        restoreHunger: Boolean = true,
        restoreEffects: Boolean = true,
        restoreFlight: Boolean = true,
    ) {
        if (restoreName)
            player.displayName(displayName)

        if (restoreInventory) {
            // `inventory` already holds the full inventory including armor slots (36-39) and the
            // offhand (40), so a single write restores everything - no separate armor/extra writes.
            player.inventory.contents = inventory.toTypedArray()
        }

        if (restoreExperience) {
            player.exp = exp
            player.totalExperience = totalExperience
            player.level = level
        }

        // The respawn point is restored regardless of online status, so a player who left mid-game
        // won't keep respawning into the game world when they come back.
        if (restoreRespawnLocation)
            player.respawnLocation = respawnLocation

        if (restoreGameMode)
            player.gameMode = gameMode

        if (restoreScoreboard)
            player.scoreboard = scoreboard

        if (restoreHealth) {
            // Restore max health before current health so a UHC-style modifier that changed the
            // player's max health is reverted, and health is clamped to the restored maximum.
            player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.baseValue = maxHealth
            player.health = min(health, maxHealth)
        }

        if (restoreEffects) {
            // Clear any game-applied effects, then re-apply the pre-game ones so nothing leaks back.
            player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
            player.addPotionEffects(activeEffects)
        }

        if (restoreFlight) {
            // Set after gameMode: setGameMode can reset flight state on some server versions.
            player.allowFlight = allowFlight
            player.isFlying = isFlying
        }

        if (restoreHunger) {
            player.foodLevel = foodLevel
            player.saturation = saturation
        }

        // Teleporting only works while the player is connected. When offline, everything else was
        // already restored above, so they get moved once they rejoin (see PlayerEvents.onPlayerJoin).
        if (restoreLocation && player.isOnline)
            player.teleport(location)
    }
}
