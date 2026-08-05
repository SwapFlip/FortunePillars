package com.marcpg.pillarperil.game.util

import com.marcpg.libpg.util.component
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.util.Configuration
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
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

    fun lobby(player: Player, index: Int, count: Int) {
        val world = resolveWorld() ?: return
        val center = Configuration.queueLocation(world)

        val radius = max(LOBBY_RADIUS, count * 1.5)
        val angle = 2 * Math.PI * index / count
        val spot = center.clone().add(radius * cos(angle), 0.0, radius * sin(angle))

        val top = Configuration.platformHeight.toInt()
        buildTower(world, spot, top)
        val feet = Location(world, spot.x, top + 2.0, spot.z)
        player.teleport(feet)
        place(player, feet)
        giveLobbyItems(player)
    }

    private fun resolveWorld(): World? {
        val map = mapOf(
            "id" to Game.generateId(),
            "mode" to Configuration.queueMode.gameInfo.namespace,
            "players" to 1,
        )
        val name = Configuration.queueWorldName(map)
        return Bukkit.getWorld(name) ?: runCatching { org.bukkit.WorldCreator(name).createWorld() }.getOrNull()
    }

    private fun buildTower(world: World, spot: Location, top: Int) {
        val x = spot.blockX
        val z = spot.blockZ
        for (y in (top - 20)..top)
            world.getBlockAt(x, y, z).type = Configuration.platformMaterial
    }

    private fun place(player: Player, feet: Location) {
        clear(player)
        val placed = mutableListOf<Block>()

        val origin = feet.block
        origin.type = Material.AIR

        val minX = origin.x - 1
        val minY = origin.y - 1
        val minZ = origin.z - 1
        val maxX = origin.x + 1
        val maxY = origin.y + 3
        val maxZ = origin.z + 1

        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val isShell = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ
            if (!isShell) continue

            val block = origin.world.getBlockAt(x, y, z)
            placed.add(block)
            block.type = Material.GLASS
        }

        cages[player.uniqueId] = placed
    }

    private fun giveLobbyItems(player: Player) {
        val leave = ItemStack(Material.RED_DYE).apply {
            val meta = itemMeta
            meta.displayName(component("Leave Queue", NamedTextColor.RED))
            itemMeta = meta
        }
        val vote = ItemStack(Material.CHEST).apply {
            val meta = itemMeta
            meta.displayName(component("Vote for Game Mode", NamedTextColor.GOLD))
            itemMeta = meta
        }
        player.inventory.setItem(0, vote)
        player.inventory.setItem(8, leave)
    }

    fun clear(player: Player) {
        cages.remove(player.uniqueId)?.forEach { it.type = Material.AIR }
        if (player.isOnline) {
            player.inventory.setItem(0, null)
            player.inventory.setItem(8, null)
        }
    }

    fun clearAll(players: Collection<Player>) = players.forEach(::clear)
}
