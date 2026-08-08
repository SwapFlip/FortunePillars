package com.swapflip.fortunepillars.event

import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.game.util.Cage
import com.swapflip.fortunepillars.game.util.GameManager
import com.swapflip.fortunepillars.game.util.QueueManager
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.QueueMethod
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent

object PlayerEvents : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = GameManager.player(event.player) ?: return

        // No longer count kills if the game has already started ending.
        if (player.game.ending) return

        // Direct kills are always credited. For indirect deaths (void, fall, explosions...), the last
        // player that damaged the victim gets the kill, as long as the hit happened within the configured
        // kill credit window and the credited player is still alive.
        val killer = event.player.killer
        val lastHit = if (killer == null && Configuration.killCreditWindow > 0 &&
            Bukkit.getCurrentTick() - player.lastDamageTick <= Configuration.killCreditWindow * 20
        ) player.lastDamagedBy else null

        val credited = killer?.let { player.game.player(it, false) } ?: lastHit?.let { player.game.player(it) }
        if (credited != null)
            credited.kills++

        player.game.players.forEach { p ->
            p.sendMessage(if (credited != null)
                p.locale().component("game.death.killed", player.player.name, credited.player.name, color = NamedTextColor.RED)
            else
                p.locale().component("game.death.eliminated", player.player.name, color = NamedTextColor.GRAY)
            )
        }

        player.eliminate()
    }

    // Every damage source is cancelled while the game hasn't started yet. This rigidly enforces
    // "invincible until the game starts": players in their cages survive PvP, falls, lava, TNT,
    // explosions, suffocation, etc. Once the countdown hits zero, damage is handled normally.
    @EventHandler
    fun onDamage(event: EntityDamageEvent) {
        val victim = GameManager.player(event.entity as? Player ?: return) ?: return

        if (!victim.game.started) {
            event.isCancelled = true
            return
        }

        if (event !is EntityDamageByEntityEvent) return
        val attacker = (event.damager as? Projectile)?.shooter as? Player ?: event.damager as? Player ?: return
        val attackerPillar = victim.game.player(attacker, false) ?: return
        victim.lastDamagedBy = attackerPillar.uuid()
        victim.lastDamageTick = Bukkit.getCurrentTick()
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val pillar = GameManager.player(event.player) ?: return
        // Respect the map's own death height when set, falling back to the global configuration.
        if (event.to.y < pillar.game.deathHeight) {
            event.player.health = 0.0
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        when (Configuration.queueMethod) {
            QueueMethod.AUTO -> bukkitRunLater(20L) { if (player.isOnline) QueueManager.add(player) } // Wait 1 second before rejoining queue.

            // Players who left mid-game (or whose game ended while they were offline) get their snapshot
            // restored on rejoin, so they never spawn back into the FortunePillars game world.
            QueueMethod.COMMAND -> bukkitRunLater(5L) {
                if (!player.isOnline) return@bukkitRunLater
                if (player in QueueManager.queue || GameManager.isInGame(player, onlyAlive = false)) return@bukkitRunLater

                val stillInGame = GameManager.player(player, onlyAlive = false)
                if (stillInGame != null) {
                    runCatching { stillInGame.restore() }
                        .onFailure { stillInGame.game.error("Could not restore ${player.name} after rejoin.", it) }
                } else if (Cage.isPluginWorld(player.world)) {
                    // Not in a game anymore, but standing where a game used to be: send them home.
                    runCatching { player.teleport(Configuration.getSpawnLocation(player.world)) }
                        .onFailure { FortunePillars.LOG.warn("Could not teleport reconnecting player ${player.name} back to the spawn.", it) }
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        QueueManager.remove(player)

        // Restore the pre-game state for anyone who was part of a game, alive or eliminated, so a
        // mid-game quit never leaves a dangling respawn point inside the game world.
        GameManager.player(player, onlyAlive = false)?.let { pillar ->
            pillar.game.eliminate(pillar)
            runCatching { pillar.restore() }
                .onFailure { pillar.game.error("Could not fully restore state for disconnected ${pillar.player.name}.", it) }
        }
    }

    // While players wait in their cages during the countdown, the inventories are locked: no
    // click-moves, no hotbar swaps, no drops, so nothing can be smuggled into the game.
    private fun inPreGame(eventPlayer: Player): Boolean {
        val game = GameManager.player(eventPlayer)?.game ?: return false
        return !game.started
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (inPreGame(player)) {
            event.isCancelled = true
            player.closeInventory()
        }
    }

    @EventHandler
    fun onSwapHandItems(event: PlayerSwapHandItemsEvent) {
        if (inPreGame(event.player)) event.isCancelled = true
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (inPreGame(event.player)) event.isCancelled = true
    }
}