package com.swapflip.fortunepillars.game.util

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.MINI_MESSAGE
import com.swapflip.fortunepillars.util.VoidChunkGenerator
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

object Cage {
    const val MAX_PLAYERS = 8
    const val LOBBY_RADIUS = 10.0

    private val cages = mutableMapOf<UUID, MutableList<Block>>()
    private val towers = mutableMapOf<UUID, MutableList<Block>>()
    private val gameCages = mutableListOf<Block>()
    // O(1) protection lookups on block-break/place events, kept in sync with the lists above.
    // Without it, every protected-block check flattened every cage list into a new collection.
    // Bukkit's CraftBlock does not override equals()/hashCode(): two Block instances for the same
    // coordinates are never equal, so keying protection on Block identity made isProtected() ALWAYS
    // return false - a complete cage-protection bypass (players could mine/place out of cages, and
    // TNT/fire could destroy them). Key on the coordinate Location instead.
    private val protectedBlocks = HashSet<Location>()

    private fun Block.toKey() = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

    private fun registerBlocks(blocks: List<Block>) {
        protectedBlocks += blocks.map { it.toKey() }
    }

    private fun unregisterBlocks(blocks: List<Block>?) {
        blocks?.forEach {
            it.type = Material.AIR
            protectedBlocks -= it.toKey()
        }
    }

    fun lobby(player: Player, index: Int, count: Int) {
        val world = resolveWorld() ?: return
        val center = Configuration.queueLocation(world)

        val radius = max(LOBBY_RADIUS, count * 1.5)
        val angle = 2 * Math.PI * index / count
        val spot = center.clone().add(radius * cos(angle), 0.0, radius * sin(angle))

        val top = Configuration.platformHeight.toInt()
        towers[player.uniqueId] = buildTower(world, spot, top)
        val feet = Location(world, spot.x + 0.5, top + 2.0, spot.z + 0.5)
        player.teleport(feet)
        place(player, feet)
        giveLobbyItems(player)
    }

    fun ensureQueueWorld(): World? {
        // A disabled queue has no world: creating one anyway would leak a void world nobody uses.
        if (!Configuration.queueEnabled) return null

        val map = mapOf(
            "id" to Game.generateId(),
            "mode" to Configuration.queueMode.gameInfo.namespace,
            "players" to 1,
        )
        val name = Configuration.queueWorldName(map)
        if ("{" in name) {
            FortunePillars.LOG.info("Queue world \"$name\" uses placeholders, skipping pre-creation.")
            return null
        }
        queueWorldName = name

        // Pre-rename installs keep their world under the "PillarPeril" name: if the configured
        // world doesn't exist (e.g. a stale default), fall back to the existing PillarPeril world
        // instead of generating a fresh void world nobody built anything in.
        Bukkit.getWorld(name)?.let { queueWorldName = it.name; return it }
        Bukkit.getWorld("PillarPeril")?.let {
            FortunePillars.LOG.info("Queue world \"$name\" does not exist; using the existing \"PillarPeril\" world instead.")
            queueWorldName = it.name
            return it
        }

        return runCatching {
            // Structures off: the queue world is a void lobby, so village/shipwreck generation
            // would only waste time and disk space on a world nobody explores.
            // keepSpawnInMemory(false): the lobby is never visited by a live game, so keeping its
            // spawn chunk resident only wastes memory.
            // setAutoSave(false): the void lobby is regenerated each start, so persisting it to disk
            // only wastes writes and can resurrect a stale world on restart.
            WorldCreator(name).generator(VoidChunkGenerator()).generateStructures(false)
                .keepSpawnInMemory(false).createWorld()?.apply { setAutoSave(false) }
        }.onFailure {
            FortunePillars.LOG.error("Could not create queue world \"$name\".", it)
        }.getOrNull()
    }

    // The queue world name captured by the last ensureQueueWorld() call, or null when it uses placeholders.
    var queueWorldName: String? = null
        private set

    // Every world the plugin ever used for a game, kept forever (worlds get reused, so removing
    // entries when a game ends would let the "stuck in the PillarPeril world" case slip through
    // again: a player who rejoins after their game ended stands in a world that is no longer an
    // active game world, but still one they must never be left in).
    private val pluginWorldNames = mutableSetOf<String>()

    fun registerPluginWorld(name: String) {
        pluginWorldNames += name
    }

    // Whether a world is owned by FortunePillars (the queue lobby or any running game), used to detect
    // players who ended up stranded in one of them.
    fun isPluginWorld(world: World): Boolean =
        world.name == queueWorldName || world.name in pluginWorldNames

    private fun resolveWorld(): World? = ensureQueueWorld()

    private fun buildTower(world: World, spot: Location, top: Int): MutableList<Block> {
        val x = spot.blockX
        val z = spot.blockZ
        val blocks = mutableListOf<Block>()
        for (y in (top - 20)..top) {
            val block = world.getBlockAt(x, y, z)
            blocks.add(block)
            block.type = Configuration.platformMaterial
        }
        registerBlocks(blocks)
        return blocks
    }

    fun gameCage(player: Player, feet: Location) {
        val placed = buildCage(feet)
        gameCages += placed
    }

    fun clearGameCages() {
        unregisterBlocks(gameCages)
        gameCages.clear()
    }

    private fun place(player: Player, feet: Location) {
        unregisterBlocks(cages.remove(player.uniqueId))
        cages[player.uniqueId] = buildCage(feet).toMutableList()
    }

    fun arena(player: Player, feet: Location) {
        unregisterBlocks(cages.remove(player.uniqueId))
        val placed = buildArenaCage(feet)
        cages[player.uniqueId] = placed.toMutableList()
        player.teleport(feet)
        giveLobbyItems(player)
    }

    private fun buildArenaCage(feet: Location): List<Block> {
        val origin = feet.block

        val placed = mutableListOf<Block>()
        for (x in (origin.x - 1)..(origin.x + 1)) {
            for (z in (origin.z - 1)..(origin.z + 1)) {
                for (y in (origin.y - 1)..(origin.y + 3)) {
                    val isWall = x == origin.x - 1 || x == origin.x + 1 || z == origin.z - 1 || z == origin.z + 1
                    val isCeiling = y == origin.y + 3
                    val isFloor = y == origin.y - 1
                    if (!isWall && !isCeiling && !isFloor) continue

                    val block = origin.world.getBlockAt(x, y, z)
                    placed.add(block)
                    block.type = Material.GLASS
                }
            }
        }
        registerBlocks(placed)
        return placed
    }

    fun isProtected(block: Block): Boolean = block.toKey() in protectedBlocks

    private fun buildCage(feet: Location): List<Block> {
        val origin = feet.block
        origin.type = Material.AIR

        val minX = origin.x - 1
        val minY = origin.y - 1
        val minZ = origin.z - 1
        val maxX = origin.x + 1
        val maxY = origin.y + 3
        val maxZ = origin.z + 1

        val placed = mutableListOf<Block>()
        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val isShell = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ
            if (!isShell) continue

            val block = origin.world.getBlockAt(x, y, z)
            placed.add(block)
            block.type = Material.GLASS
        }
        registerBlocks(placed)
        return placed
    }

    private fun giveLobbyItems(player: Player) {
        // Joining the queue wipes the inventory first, so nothing from the outside (items held
        // from a game, a previous queue session or a plugin chest) survives into the lobby.
        player.inventory.clear()
        val leave = ItemStack(Material.RED_DYE).apply {
            val meta = itemMeta
            meta.displayName(MINI_MESSAGE.deserialize(Configuration.menuLeaveItemName))
            itemMeta = meta
        }
        val vote = ItemStack(Material.CHEST).apply {
            val meta = itemMeta
            meta.displayName(MINI_MESSAGE.deserialize(Configuration.menuMapItemName))
            itemMeta = meta
        }
        player.inventory.setItem(0, vote)
        player.inventory.setItem(8, leave)
    }

    fun clear(player: Player) {
        unregisterBlocks(cages.remove(player.uniqueId))
        unregisterBlocks(towers.remove(player.uniqueId))
        if (player.isOnline) {
            player.exp = 0.0f
            player.level = 0
            player.inventory.setItem(0, null)
            player.inventory.setItem(8, null)
        }
    }

    // Only clears cage/tower blocks for players who are no longer queued. A game start that drains
    // the queue down to queueMaxPlayers leaves any overflow players still queued, and blanket-clearing
    // would strand them (their tower turns to air and drops them). The game's own players were already
    // cleared by clearAll().
    fun clearTowers(preserve: Collection<Player> = emptyList()) {
        val keep = preserve.mapTo(mutableSetOf()) { it.uniqueId }
        cages.keys.minus(keep).forEach { uuid -> unregisterBlocks(cages.remove(uuid)) }
        towers.keys.minus(keep).forEach { uuid -> unregisterBlocks(towers.remove(uuid)) }
    }

    fun clearAll(players: Collection<Player>) = players.forEach(::clear)
}