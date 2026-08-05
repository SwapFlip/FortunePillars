package com.marcpg.pillarperil.event

import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.marcpg.pillarperil.game.util.GameManager
import com.marcpg.pillarperil.game.util.QueueManager
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.QueueMethod
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent

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

    @EventHandler(ignoreCancelled = true)
    fun onPlayerDamage(event: EntityDamageByEntityEvent) {
        val victim = GameManager.player(event.entity as? Player ?: return) ?: return
        val attacker = (event.damager as? Projectile)?.shooter as? Player ?: event.damager as? Player ?: return
        val attackerPillar = victim.game.player(attacker, false) ?: return

        // PvP is disabled during the pre-game countdown, while players are still inside their cages.
        if (victim.game.itemCountdown.get() > 0) {
            event.isCancelled = true
            return
        }

        victim.lastDamagedBy = attackerPillar.uuid()
        victim.lastDamageTick = Bukkit.getCurrentTick()
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        if (event.to.y < Configuration.deathHeight) {
            GameManager.player(event.player) ?: return
            event.player.health = 0.0
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (Configuration.queueMethod == QueueMethod.AUTO)
            bukkitRunLater(20L) { if (event.player.isOnline) QueueManager.add(event.player) } // Wait 1 second before rejoining queue.
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        QueueManager.remove(event.player)
        GameManager.player(event.player)?.eliminate()
    }
}
