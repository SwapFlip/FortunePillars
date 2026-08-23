package com.swapflip.fortunepillars.event

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent
import com.destroystokyo.paper.event.server.ServerTickEndEvent
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.game.util.Cage
import com.swapflip.fortunepillars.game.util.GameManager
import com.swapflip.fortunepillars.game.util.QueueManager
import com.swapflip.fortunepillars.player.SpectatorManager
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.Ticking
import com.swapflip.fortunepillars.util.consumeHeldItem
import com.swapflip.fortunepillars.util.setFuseTicks
import io.papermc.paper.event.entity.EntityPortalReadyEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.world.PortalCreateEvent

object GameEvents : Listener {
    private val tntType: EntityType by lazy {
        runCatching { EntityType.valueOf("PRIMED_TNT") }.getOrElse { EntityType.valueOf("TNT") }
    }

    @EventHandler(ignoreCancelled = true)
    fun onServerTickEnd(event: ServerTickEndEvent) {
        val tick = Ticking.Tick(event.tickNumber)

        // A single broken game or queue tick must never take down the whole tick pipeline: each
        // one is isolated so the rest keep running and the error surfaces in the log.
        runCatching { QueueManager.tick(tick) }
            .onFailure { FortunePillars.LOG.warn("[Queue] Tick failed: ${it.javaClass.simpleName}: ${it.message}", it) }
        GameManager.games.values.toList().forEach { game ->
            runCatching { game.tick(tick) }
                .onFailure { FortunePillars.LOG.warn("[Game] Tick failed for ${game.world.name}: ${it.javaClass.simpleName}: ${it.message}", it) }
        }

        // Players stranded in a plugin-owned world (queue or game world) without being queued or
        // in a game - e.g. after a failed game start or a queue world name that placeholders
        // resolved differently - get sent back to the lobby once a second. This is the safety net
        // behind the join/world-change handlers: whatever put them there, they can never be stuck
        // in the PillarPeril world. Ops and fortunepillars.bypass holders are exempt, so a manual
        // /mvtp into a plugin world (e.g. to check the lobby) actually stays put.
        if (tick.number % 20 == 0) {
            Bukkit.getOnlinePlayers().forEach { p ->
                if (Cage.isPluginWorld(p.world) && QueueManager.currentQueueOf(p) == null && !SpectatorManager.isSpectating(p) && GameManager.player(p, onlyAlive = false) == null && !(p.isOp || p.hasPermission("fortunepillars.bypass")))
                    runCatching { p.teleport(Configuration.getLobbySpawn()) }
                        .onFailure { FortunePillars.LOG.warn("Could not teleport stranded player ${p.name} back to the lobby.", it) }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntitySpawn(event: EntitySpawnEvent) {
        GameManager.getClosestGame(event.location)?.buildings?.registerSpawn(event.entity)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerSetSpawn(event: PlayerSetSpawnEvent) {
        // Only in-game players are barred from setting their respawn point to a bed or anchor:
        // everyone else (lobby, queue, other worlds) may do so freely.
        if ((event.cause == PlayerSetSpawnEvent.Cause.BED || event.cause == PlayerSetSpawnEvent.Cause.RESPAWN_ANCHOR)
            && GameManager.isInGame(event.player))
            event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        // A falling block from a game (e.g. Ablockalypse's drops) landed: record the landing spot
        // with its pre-landing state - the event fires before the change - so the arena reset can
        // restore the map exactly as it was. The bounds check is skipped on purpose: blocks that
        // land on top of towers above the arena's max height are still the game's responsibility,
        // and an unregistered landing spot would survive into the next game forever.
        if (event.entity is FallingBlock)
            GameManager.getClosestGame(event.block.location, withinBounds = false)?.buildings?.registerPlace(event.block.location, event.block.blockData)

        if (event.block.type == Material.END_PORTAL_FRAME && GameManager.isWithinGame(event.block.location))
            event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPortalCreate(event: PortalCreateEvent) {
        if (event.reason != PortalCreateEvent.CreateReason.FIRE) return

        if ((event.entity != null && GameManager.isPartOfGame(event.entity!!)) || GameManager.isWithinGame(event.blocks.first().location))
            event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityPortalReady(event: EntityPortalReadyEvent) {
        if (GameManager.isPartOfGame(event.entity))
            event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerPortal(event: PlayerPortalEvent) {
        // onlyAlive=false: eliminated players are still part of the game - they must not be able
        // to portal out of the arena and leave the game world behind.
        if (GameManager.isInGame(event.player, onlyAlive = false))
            event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        // TNT: placing it always primes it with a 3s fuse, so it can never sit around as a passive
        // block that players have to light with flint & steel first.
        val player = event.player
        val pillar = GameManager.player(player)
        // The celebrating winner must not modify the arena: no block placing, no TNT priming.
        if (pillar?.winnerProtected == true) {
            event.isCancelled = true
            return
        }
        val held = player.inventory.itemInMainHand
        if (pillar != null && held.type == Material.TNT) {
            event.isCancelled = true
            runCatching {
                val loc = event.blockPlaced.location.add(0.5, 0.5, 0.5)
                val entity = player.world.spawnEntity(loc, tntType)
                entity.setFuseTicks(60)
                // Consumed through the held slot rather than mutating `held` directly, so the last
                // TNT never leaves a ghost amount-0 stack behind.
                player.consumeHeldItem()
            }.onFailure { pillar.game.error("Could not prime ${player.name}'s TNT.", it) }
            return
        }

        modify(player, event.blockPlaced.location, event.blockReplacedState.blockData, event)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockFromTo(event: BlockFromToEvent) {
        // Fluids spread beyond the block that was placed: every spot a flow reaches must be
        // registered with the closest game, or the spread (LavaFloor lava, bucket spills) would
        // survive into the next game as permanent pools that the arena reset never touches. Only
        // loaded chunks are registered: an unloaded chunk cannot spread right now, and a distant
        // flow would otherwise bloat the reset with entries that force chunk loads at cleanup.
        val source = event.block.type
        if (source != Material.WATER && source != Material.LAVA) return
        val destination = event.toBlock
        if (!destination.world.isChunkLoaded(destination.chunk.x, destination.chunk.z)) return
        GameManager.getClosestGame(destination.location, withinBounds = false)?.buildings?.registerPlace(destination.location)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockMultiPlace(event: BlockMultiPlaceEvent) {
        event.replacedBlockStates.forEach { modify(event.player, it.location, it.blockData, event) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        modify(event.player, event.block.location, event.block.blockData, event)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerBucketEmpty(event: PlayerBucketEmptyEvent) {
        modify(event.player, event.block.location, event.block.blockData, event)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerBucketFill(event: PlayerBucketFillEvent) {
        modify(event.player, event.block.location, event.block.blockData, event)
    }

    private fun modify(player: Player, location: Location, data: BlockData, event: org.bukkit.event.Cancellable) {
        val pillar = GameManager.player(player, onlyAlive = false) ?: return
        // The celebrating winner must not modify the arena either: no mining, no buckets, no
        // multi-placing during the victory celebration.
        if (pillar.winnerProtected) {
            event.isCancelled = true
            return
        }
        // Cage blocks are part of the pre-game state, not the arena: while the cages are shut they
        // must not be mined or replaced, or a player could break their way out before the fight.
        if (Cage.isProtected(location.block)) {
            event.isCancelled = true
            return
        }
        // Eliminated players must not change the arena: the change is cancelled, and the spot is
        // registered with the buildings so the arena reset restores it anyway (belt and braces).
        if (GameManager.player(player) == null)
            event.isCancelled = true
        pillar.game.buildings.registerPlace(location, data)
    }
}
