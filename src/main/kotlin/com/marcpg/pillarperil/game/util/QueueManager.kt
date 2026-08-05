package com.marcpg.pillarperil.game.util

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.Registry
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameCompanion
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.Ticking
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

object QueueManager : Ticking {
    const val RED_COLORS = "#CC2222:#FF8888"
    const val GREEN_COLORS = "#22CC22:#88FF88"

    val queue = ArrayDeque<Player>()

    private val votes = mutableMapOf<UUID, String>()

    private var phase = 0.0

    fun recordVote(player: Player, mode: String) {
        votes[player.uniqueId] = mode
    }

    fun voteCounts(): Map<String, Int> = votes.values.groupingBy { it }.eachCount()

    fun add(player: Player) {
        if (!Configuration.queueEnabled || player in queue || GameManager.isInGame(player)) return

        queue.addLast(player)
        Cage.lobby(player, queue.size - 1, queue.size)

        if (Configuration.queueCheckIntervalSecs == -1)
            check()
    }

    fun remove(player: Player) {
        if (!Configuration.queueEnabled) return

        queue.remove(player)
        Cage.clear(player)

        if (Configuration.queueCheckIntervalSecs == -1)
            check()
    }

    override fun tick(tick: Ticking.Tick) {
        if (!Configuration.queueEnabled) return

        if (Configuration.queueCheckIntervalSecs >= 1) {
            if (tick.isInInterval(0, Configuration.queueCheckInterval))
                check()
        }

        queue.forEach { it.sendActionBar(it.locale().component(
            "queue.actionbar",
            queue.size.toString(), Configuration.queueMinPlayers.toString(),
            color = if (queue.size >= Configuration.queueMinPlayers) NamedTextColor.GREEN else NamedTextColor.RED
        )) }

        queue.forEach { it.sendActionBar(MiniMessage.miniMessage().deserialize("<gradient:${if (queue.size >= Configuration.queueMinPlayers) GREEN_COLORS else RED_COLORS}:$phase>${it.locale().string("queue.actionbar", queue.size.toString(), Configuration.queueMinPlayers.toString())}</gradient>")) }
    }

    private fun check() {
        if (queue.size < Configuration.queueMinPlayers)
            return

        val players = MutableList(queue.size) { queue.removeFirst() }
        val voted = players.mapNotNull { votes[it.uniqueId] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val mode = Registry.modes[voted] ?: Configuration.queueMode

        players.forEach { votes.remove(it.uniqueId) }
        startGame(players, mode)
    }

    private fun startGame(players: List<Player>, mode: GameCompanion<*>) {
        Cage.clearAll(players)

        val id = Game.generateId()
        val map = mutableMapOf(
            "id" to id,
            "mode" to mode.gameInfo.namespace,
            "players" to players.size,
        )

        Configuration.queuePreCommands.forEach { PillarPeril.sendCommand(it(map)) }

        val worldName = Configuration.queueWorldName(map)
        val world = Bukkit.getWorld(worldName) ?: runCatching { org.bukkit.WorldCreator(worldName).createWorld() }
            .onFailure { PillarPeril.LOG.error("Could not create game world \"$worldName\".", it) }
            .getOrNull()
        if (world == null) {
            players.forEach {
                it.sendMessage(component("Configured world \"$worldName\" does not exist, which means the game cannot start.", NamedTextColor.RED))
                it.sendMessage(component("Please notify an admin of the server.", NamedTextColor.RED))
            }
            return
        }

        val location = Configuration.queueLocation(world)

        map += mapOf(
            "world" to location.world.name,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
        )
        Configuration.queuePostCommands.forEach { PillarPeril.sendCommand(it(map)) }

        // Actually start the game after doing like 20 other things:
        // TODO: Supply list of modifiers here:
        mode.constructGame(id, location, players, listOf()).init()
    }
}
