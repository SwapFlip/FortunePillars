package com.marcpg.pillarperil.game.util

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.VoidChunkGenerator
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
        val map = mapOf(
            "id" to Game.generateId(),
            "mode" to Configuration.queueMode.gameInfo.namespace,
            "players" to 1,
        )
        val name = Configuration.queueWorldName(map)
        if ("{" in name) {
            PillarPeril.LOG.info("Queue world \"$name\" uses placeholders, skipping pre-creation.")
            return null
        }
        queueWorldName = name

        return Bukkit.getWorld(name) ?: runCatching {
            WorldCreator(name).generator(VoidChunkGenerator()).createWorld()
        }.onFailure {
            PillarPeril.LOG.error("Could not create queue world \"$name\".", it)
        }.getOrNull()
    }

    // The queue world name captured by the last ensureQueueWorld() call, or null when it uses placeholders.
    private var queueWorldName: String? = null

    // Whether a world is owned by PillarPeril (the queue lobby or any running game), used to detect
    // players who ended up stranded in one of them.
    fun isPluginWorld(world: World): Boolean =
        world.name == queueWorldName || GameManager.games.values.any { it.world.name == world.name }

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
        return blocks
    }

    fun gameCage(player: Player, feet: Location) {
        val placed = buildCage(feet)
        gameCages += placed
    }

    fun clearGameCages() {
        gameCages.forEach { it.type = Material.AIR }
        gameCages.clear()
    }

    private fun place(player: Player, feet: Location) {
        cages.remove(player.uniqueId)?.forEach { it.type = Material.AIR }
        cages[player.uniqueId] = buildCage(feet).toMutableList()
    }

    fun arena(player: Player, feet: Location) {
        cages.remove(player.uniqueId)?.forEach { it.type = Material.AIR }
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
                for (y in origin.y..(origin.y + 3)) {
                    val isWall = x == origin.x - 1 || x == origin.x + 1 || z == origin.z - 1 || z == origin.z + 1
                    val isCeiling = y == origin.y + 3
                    if (!isWall && !isCeiling) continue

                    val block = origin.world.getBlockAt(x, y, z)
                    placed.add(block)
                    block.type = Material.GLASS
                }
            }
        }
        return placed
    }

    fun isProtected(block: Block): Boolean =
        block in cages.values.flatten() || block in towers.values.flatten() || block in gameCages

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
        return placed
    }

    private fun giveLobbyItems(player: Player) {
        val leave = ItemStack(Material.RED_DYE).apply {
            val meta = itemMeta
            meta.displayName(player.locale().component("queue.item.leave", color = NamedTextColor.RED))
            itemMeta = meta
        }
        val vote = ItemStack(Material.CHEST).apply {
            val meta = itemMeta
            meta.displayName(player.locale().component("queue.item.vote", color = NamedTextColor.GOLD))
            itemMeta = meta
        }
        player.inventory.setItem(0, vote)
        player.inventory.setItem(8, leave)
    }

    fun clear(player: Player) {
        cages.remove(player.uniqueId)?.forEach { it.type = Material.AIR }
        towers.remove(player.uniqueId)?.forEach { it.type = Material.AIR }
        if (player.isOnline) {
            player.exp = 0.0f
            player.level = 0
            player.inventory.setItem(0, null)
            player.inventory.setItem(8, null)
        }
    }

    fun clearTowers() {
        cages.forEach { (_, blocks) -> blocks.forEach { it.type = Material.AIR } }
        towers.forEach { (_, blocks) -> blocks.forEach { it.type = Material.AIR } }
        cages.clear()
        towers.clear()
    }

    fun clearAll(players: Collection<Player>) = players.forEach(::clear)
}