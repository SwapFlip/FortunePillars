package com.swapflip.fortunepillars.event

import com.marcpg.libpg.display.start
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.game.util.Cage
import com.swapflip.fortunepillars.game.util.GameManager
import com.swapflip.fortunepillars.game.util.QueueManager
import com.swapflip.fortunepillars.player.SpecialItems
import com.swapflip.fortunepillars.player.SpectatorManager
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.QueueMethod
import com.swapflip.fortunepillars.util.chatComponent
import com.swapflip.fortunepillars.util.consumeHeldItem
import com.swapflip.fortunepillars.util.escapeTags
import com.swapflip.fortunepillars.util.getAttributeSafe
import com.swapflip.fortunepillars.util.Achievements
import com.swapflip.fortunepillars.util.MINI_MESSAGE
import com.swapflip.fortunepillars.util.PlayerStats
import com.swapflip.fortunepillars.util.potionEffectType
import com.swapflip.fortunepillars.util.playSoundSafe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import java.util.UUID
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.SmallFireball
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

object PlayerEvents : Listener {
    // Throttles the per-hit feedback sound so a flurry of damage doesn't machine-gun the sfx.
    private val lastHitSoundTick = mutableMapOf<UUID, Int>()
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
        if (credited != null) {
            credited.kills++
            PlayerStats.addKill(credited.uuid())
            if (credited.kills >= 5 && credited.player.isOnline) Achievements.grant(credited.player, "killstreak")
            // Personal "you got a kill" feedback so a knockout feels rewarding.
            if (credited.player.isOnline) {
                credited.player.sendActionBar(credited.player.locale().component("game.kill.you", player.player.name.escapeTags(), color = NamedTextColor.GOLD))
                credited.player.playSoundSafe(Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f)
            }
            // Rampage cue: 3 kills within the window announces the streak to everyone.
            if (credited.registerKill()) {
                credited.game.players.forEach { p ->
                    p.sendActionBar(p.locale().component("game.rampage", credited.player.name.escapeTags(), color = NamedTextColor.GOLD))
                }
                credited.game.players.playSoundSafe(Sound.ENTITY_BLAZE_DEATH, 0.8f, 1.0f)
                Achievements.grant(credited.player, "rampage")
            }
        }

        // The victim gets a brief death flash + sound, so being eliminated reads clearly even
        // amid the kill-feed chat line.
        if (player.player.isOnline) {
            player.player.showTitle(Title.title(Component.empty(), player.player.locale().component("game.death.flash", color = NamedTextColor.RED)))
            player.player.playSoundSafe(Sound.ENTITY_PLAYER_DEATH, 0.8f, 1.0f)
        }

        player.game.players.forEach { p ->
            p.sendMessage(if (credited != null)
                p.locale().chatComponent("game.death.killed", player.player.name.escapeTags(), credited.player.name.escapeTags())
            else
                p.locale().chatComponent("game.death.eliminated", player.player.name.escapeTags())
            )
        }

        player.eliminate()
    }

    // Players are never invincible: no damage source is cancelled. This only tracks the last hit
    // to credit void/fall knock-offs, so direct killers and indirect deaths are both credited.
    // ignoreCancelled: a hit another plugin already cancelled must never count as a hit, or the
    // attacker would be credited with a kill they never landed.
    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = GameManager.player(event.entity as? Player ?: return) ?: return

        // The celebrating winner is invincible: no damage source can hurt them during the victory
        // celebration. Void falls never reach this point - the move handler rescues them first.
        if (victim.winnerProtected) {
            event.isCancelled = true
            return
        }

        // The Super Star shield absorbs damage events instead of hits: any source (falls,
        // explosions, attacks) consumes one of the 2 charges while the shield is active.
        // Void damage is exempt: a shielded player must never hover in the void forever,
        // unable to die and unable to be eliminated.
        if (victim.starShieldActive && event.cause != EntityDamageEvent.DamageCause.VOID) {
            event.isCancelled = true
            victim.starShieldHits--
            // The shield shatters when the last charge is used up; the expiry path lives in
            // Game.tick, so the sound and message fire exactly once in both cases.
            victim.playSoundSafe(if (victim.starShieldHits <= 0) Sound.ITEM_SHIELD_BREAK else Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f)
            victim.player.sendActionBar(victim.player.locale().component(
                if (victim.starShieldHits > 0) "special.super-star.actionbar" else "special.super-star.depleted",
                victim.starShieldHits.toString(), color = NamedTextColor.GOLD))
            return
        }

        if (event !is EntityDamageByEntityEvent) return
        val attacker = (event.damager as? Projectile)?.shooter as? Player ?: event.damager as? Player ?: return
        // Only alive players can land hits worth tracking: a hit from an eliminated (spectator)
        // player must never credit them with a kill.
        val attackerPillar = victim.game.player(attacker) ?: return

        victim.lastDamagedBy = attackerPillar.uuid()
        victim.lastDamageTick = Bukkit.getCurrentTick()

        // Throttled hit feedback: a soft hurt sound on landed melee/ projectile damage, rate-limited
        // per victim so rapid hits don't spam the audio.
        val now = Bukkit.getCurrentTick()
        val last = lastHitSoundTick.getOrDefault(victim.uuid(), Int.MIN_VALUE)
        if (now - last >= 6) {
            lastHitSoundTick[victim.uuid()] = now
            victim.player.playSoundSafe(Sound.ENTITY_PLAYER_HURT, 0.5f, 1.0f)
        }
    }

    // Special items used with a right-click: Super Star (shield), Fireball (projectile) and
    // Aid Platform (slime platform under the feet).
    // No ignoreCancelled here on purpose: Paper fires PlayerInteractEvent as already-cancelled
    // when the vanilla behavior for the clicked air/block would do nothing (which is the case
    // for non-usable items like a nether star, fire charge or slime block) - with
    // ignoreCancelled = true those right-clicks would never reach us and the specials would only
    // work when aimed at a block.
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val pillar = GameManager.player(player) ?: return
        val item = event.item ?: return
        val special = SpecialItems.of(item) ?: return

        event.isCancelled = true

        when (special) {
            SpecialItems.SUPER_STAR -> {
                player.consumeHeldItem()
                pillar.starShieldHits = 2
                pillar.starShieldUntil = Bukkit.getCurrentTick() + 30 * 20
                player.playSoundSafe(Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.2f)
                player.sendActionBar(player.locale().component("special.super-star.activated", color = NamedTextColor.GOLD))
            }
            SpecialItems.FIREBALL -> {
                player.consumeHeldItem()
                val fireball = player.launchProjectile(SmallFireball::class.java, player.location.direction.multiply(1.5))
                fireball.shooter = player
                player.playSoundSafe(Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.0f)
            }
            SpecialItems.AID_PLATFORM -> {
                player.consumeHeldItem()
                val feet = player.location
                for (dx in -1..1) {
                    for (dz in -1..1) {
                        val loc = Location(player.world, feet.blockX + dx + 0.5, feet.blockY - 1.0, feet.blockZ + dz + 0.5)
                        // Registered with the game's buildings so the platform resets with the arena.
                        pillar.game.buildings.registerPlace(loc)
                        // Physics-enabled placement: with applyPhysics=false the vanilla engine skips
                        // pushEntitiesOutOfBlock, so a player standing in the platform's space stays
                        // embedded in a solid block (unable to move or bounce) until a later update -
                        // the "solid for a split second" that breaks slime clutches.
                        loc.block.type = Material.SLIME_BLOCK
                    }
                }
                player.playSoundSafe(Sound.BLOCK_SLIME_BLOCK_PLACE, 1.0f, 1.0f)
            }
            SpecialItems.LEVITATION_FEATHER -> {
                player.consumeHeldItem()
                // A short, gentle lift: enough to clear a pillar lip or dodge a hazard, but it times
                // out well before it can float a player into the void on its own.
                player.addPotionEffect(PotionEffect(potionEffectType("LEVITATION") ?: PotionEffectType.LEVITATION, 6 * 20, 1))
                player.playSoundSafe(Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.2f)
                player.sendActionBar(player.locale().component("special.levitation-feather.activated", color = NamedTextColor.AQUA))
            }
            else -> Unit
        }
    }

    // A death in the game world respawns the player at the world spawn (usually 0,0) - and the
    // eliminate() spectator teleport only runs ~950ms later, so without this the respawning
    // player would flash at the world spawn (and inside the world) before being moved.
    @EventHandler(ignoreCancelled = true)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val pillar = GameManager.player(player, onlyAlive = false)
        if (pillar != null) {
            if (player.world != pillar.game.world) return
            event.respawnLocation = pillar.game.safeSpectatorSpot()
            return
        }
        // The game that killed this player is already over (their death landed while the end
        // sequence was running, or right after the cleanup sent everyone home): respawning into a
        // plugin world puts them at the world spawn (0 0 on standard setups) - the void, where
        // they die and respawn forever, stuck in the death screen. Anchor respawns outside any
        // game at the lobby instead.
        if (Cage.isPluginWorld(player.world))
            event.respawnLocation = Configuration.getLobbySpawn()
    }

    // Fireballs are explosives: on impact they break the arena (registered in QueueEvents so the
    // craters reset with the map) and never light fires - cancelling the event stops the vanilla
    // SmallFireball block-ignition, and the explosion itself is created fireless. Ender pearls
    // that smash into the arena border (BARRIER) are simply consumed: no teleport through the wall.
    @EventHandler(ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val shooter = (event.entity.shooter as? Player)?.let { GameManager.player(it) } ?: return
        if (shooter.game.ending) return

        when (event.entity) {
            is SmallFireball -> {
                event.isCancelled = true
                val projectile = event.entity
                projectile.remove()
                val loc = event.hitBlock?.location?.add(0.5, 0.5, 0.5) ?: projectile.location
                // setFire = false: explosions break blocks but never spread flames.
                // Power 1.5 (down from 2.5): much less knockback, so a fireball hit shoves rather
                // than launches - Bukkit ties KB to the explosion power, so both shrink together.
                shooter.game.world.createExplosion(loc, 1.5f, false, true)
            }
            is EnderPearl -> {
                if (event.hitBlock?.type == Material.BARRIER) {
                    event.isCancelled = true
                    event.entity.remove()
                }
            }
            else -> Unit
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val pillar = GameManager.player(event.player) ?: return
        // The void kill belongs to the running game: after the end sequence started, nobody may be
        // killed anymore (their elimination handlers are long gone).
        if (pillar.game.ending) return
        // The void kill also only applies once the fight actually started: during the cage and
        // countdown phase a player who slips below the death height (e.g. an overflow cage on a
        // floorless arena) must be rescued, not eliminated.
        if (!pillar.game.started) {
            val below = event.to ?: return
            if (below.y < pillar.game.deathHeight) {
                event.player.teleport(pillar.game.safeSpectatorSpot())
                event.player.health = event.player.getAttributeSafe("MAX_HEALTH")?.value ?: 20.0
                event.player.fallDistance = 0.0f
            }
            return
        }
        // Respect the map's own death height when set, falling back to the global configuration.
        val to = event.to ?: return
        if (to.y < pillar.game.deathHeight) {
            // The celebrating winner can't die to the void: they are sent back to the spectator
            // spot with full health, still in survival, instead of being killed.
            if (pillar.winnerProtected) {
                event.player.teleport(pillar.game.safeSpectatorSpot())
                event.player.health = event.player.getAttributeSafe("MAX_HEALTH")?.value ?: 20.0
                event.player.fallDistance = 0.0f
                return
            }
            event.player.health = 0.0
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        // Warm this player's stats into the cache so cosmetics/leaderboards read memory, not disk.
        PlayerStats.get(player.uniqueId)
        when (Configuration.queueMethod) {
            QueueMethod.AUTO -> bukkitRunLater(20L) { if (player.isOnline) QueueManager.joinMap(player, "") } // Wait 1 second before rejoining queue.

            // Players who left mid-game (or whose game ended while they were offline) get their snapshot
            // restored on rejoin, so they never spawn back into the FortunePillars game world.
            QueueMethod.COMMAND -> bukkitRunLater(5L) {
                if (!player.isOnline) return@bukkitRunLater
                if (QueueManager.currentQueueOf(player) != null || GameManager.isInGame(player, onlyAlive = false)) return@bukkitRunLater

                val stillInGame = GameManager.player(player, onlyAlive = false)
                if (stillInGame != null) {
                    // Only restore players who are still active participants. Someone who was
                    // eliminated (or quit, which eliminates them) while the match is still running
                    // must not be teleported back into the live arena as a non-playing ghost - send
                    // them to the lobby instead.
                    if (GameManager.player(player, onlyAlive = true) != null) {
                        runCatching { stillInGame.restore() }
                            .onFailure { stillInGame.game.error("Could not restore ${player.name} after rejoin.", it) }
                    } else {
                        runCatching {
                            player.gameMode = Configuration.spawnGameMode
                            player.teleport(Configuration.getLobbySpawn())
                        }.onFailure { FortunePillars.LOG.warn("Could not teleport reconnecting player ${player.name} back to the lobby.", it) }
                    }
                } else if (Cage.isPluginWorld(player.world)) {
                    // Not in a game anymore, but standing where a game used to be (or in the queue
                    // world): send them home. The game mode is reset too, so a player who quit as
                    // a spectator or in a frozen state doesn't come back frozen in the void.
                    runCatching {
                        player.gameMode = Configuration.spawnGameMode
                        player.teleport(Configuration.getLobbySpawn())
                    }.onFailure { FortunePillars.LOG.warn("Could not teleport reconnecting player ${player.name} back to the lobby.", it) }
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        SpectatorManager.stop(player)
        QueueManager.leaveQueue(player)

        // Restore the pre-game state for anyone who was part of a game, alive or eliminated, so a
        // mid-game quit never leaves a dangling respawn point inside the game world.
        GameManager.player(player, onlyAlive = false)?.let { pillar ->
            // Stop the display widgets so their scheduled tasks don't keep running for a
            // disconnected player.
            pillar.stopWidgets()
            pillar.game.eliminate(pillar)
            runCatching { pillar.restore() }
                .onFailure { pillar.game.error("Could not fully restore state for disconnected ${pillar.player.name}.", it) }
        }
    }

    // The scoreboard and action bar only belong to the game's world (the PillarPeril world on
    // standard setups): leaving it hides them, returning to it brings them back.
    @EventHandler(ignoreCancelled = true)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val player = event.player

        // Leaving the map's world ends a /pof spectate session: the previous game mode returns.
        SpectatorManager.stop(player)

        // Leaving the queue world while queued means leaving the queue - no ghost queuing from
        // anywhere else on the server.
        if (QueueManager.currentQueueOf(player) != null && Cage.isPluginWorld(event.from) && !Cage.isPluginWorld(player.world)) {
        QueueManager.leaveQueue(player)
            player.sendMessage(player.locale().chatComponent("queue.leave.world"))
        }

        // Leaving the game world mid-match is a regular death: the last damager gets the kill (if
        // within the kill credit window) and the leaver is eliminated and restored. The scoreboard
        // and action bar only belong to the game's world, so they are stopped here as well.
        val pillar = GameManager.player(player) ?: return
        if (player.world == pillar.game.world) {
            // Back in the game world: bring the display widgets back.
            pillar.startWidgets()
            return
        }

        pillar.stopWidgets()

        val lastHit = if (Configuration.killCreditWindow > 0 &&
            Bukkit.getCurrentTick() - pillar.lastDamageTick <= Configuration.killCreditWindow * 20
        ) pillar.lastDamagedBy else null
        val credited = lastHit?.let { pillar.game.player(it) }
        if (credited != null) {
            credited.kills++
            PlayerStats.addKill(credited.uuid())
            if (credited.kills >= 5 && credited.player.isOnline) Achievements.grant(credited.player, "killstreak")
        }

        pillar.game.players.forEach { p ->
            p.sendMessage(if (credited != null)
                p.locale().chatComponent("game.death.killed", player.name.escapeTags(), credited.player.name.escapeTags())
            else
                p.locale().chatComponent("game.death.eliminated", player.name.escapeTags())
            )
        }

        pillar.eliminate()
        runCatching { pillar.restore() }
            .onFailure { pillar.game.error("Could not restore ${player.name} after leaving the game world.", it) }
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
        // The multi picker is a game menu that opens before the cages release: it must
        // not be cancelled or force-closed by the pre-game inventory lock, or the first click
        // would instantly confirm (with an empty selection) and close the picker.
        if (player.openInventory.title() == QueueEvents.multiTitle) return
        if (inPreGame(player)) {
            event.isCancelled = true
            // Reverts the click on the client instead of slamming the inventory shut, so the
            // player keeps looking at their (locked) items without a flicker every click.
            player.updateInventory()
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