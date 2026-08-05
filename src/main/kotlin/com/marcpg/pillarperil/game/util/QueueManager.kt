package com.marcpg.pillarperil.game.util

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.Registry
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameCompanion
import com.marcpg.pillarperil.map.MapManager
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

    data class Vote(val mode: String? = null, val type: String? = null, val time: Int? = null)

    val queue = ArrayDeque<Player>()

    private val votes = mutableMapOf<UUID, Vote>()

    private var phase = 0.0

    fun recordVote(player: Player, mode: String? = null, type: String? = null, time: Int? = null) {
        val previous = votes[player.uniqueId] ?: Vote()
        votes[player.uniqueId] = Vote(mode ?: previous.mode, type ?: previous.type, time ?: previous.time)
    }

    fun modeVoteCounts(): Map<String, Int> = votes.values.mapNotNull { it.mode }.groupingBy { it }.eachCount()
    fun typeVoteCounts(): Map<String, Int> = votes.values.mapNotNull { it.type }.groupingBy { it }.eachCount()
    fun timeVoteCounts(): Map<Int, Int> = votes.values.mapNotNull { it.time }.groupingBy { it }.eachCount()

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
        votes.remove(player.uniqueId)
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

        queue.forEach { it.sendActionBar(MiniMessage.miniMessage().deserialize("<gradient:${if (queue.size >= Configuration.queueMinPlayers) GREEN_COLORS else RED_COLORS}:$phase>${it.locale().string("queue.actionbar", queue.size.toString(), Configuration.queueMinPlayers.toString())}</gradient>")) }
    }

    private fun <T> List<T>.mostVoted(default: T): T = groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: default

    private fun check() {
        if (queue.size < Configuration.queueMinPlayers)
            return

        val players = MutableList(queue.size) { queue.removeFirst() }
        val votesList = players.mapNotNull { votes[it.uniqueId] }

        val modeName = votesList.mapNotNull { it.mode }.mostVoted(Configuration.queueMode.gameInfo.namespace)
        val typeName = votesList.mapNotNull { it.type }.mostVoted("normal")
        val itemTime = votesList.mapNotNull { it.time }.mostVoted(Configuration.queueDefaultTime)

        players.forEach { votes.remove(it.uniqueId) }
        val mode = Registry.modes[modeName] ?: Configuration.queueMode

        startGame(players, mode, typeName, itemTime)
    }

    private fun startGame(players: List<Player>, mode: GameCompanion<*>, typeName: String, itemTime: Int) {
        Cage.clearAll(players)
        Cage.clearTowers()

        val id = Game.generateId()
        val placeholders = mutableMapOf(
            "id" to id,
            "mode" to mode.gameInfo.namespace,
            "players" to players.size,
        )

        Configuration.queuePreCommands.forEach { PillarPeril.sendCommand(it(placeholders)) }

        val worldName = Configuration.queueWorldName(placeholders)
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

        val arenaMap = MapManager.pickMap(players.size, world)
        val location = arenaMap?.originLocation(world) ?: Configuration.queueLocation(world)

        placeholders += mapOf(
            "world" to location.world.name,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
        )
        Configuration.queuePostCommands.forEach { PillarPeril.sendCommand(it(placeholders)) }

        val game = mode.constructGame(id, location, players, listOf())
        game.map = arenaMap
        game.modifiers = listOfNotNull(Registry.modifiers[typeName]?.constructModifier(game))
        game.customItemCountdown = { if (typeName == "speedrunner") 2L else itemTime.toLong() }

        game.init()
    }
}