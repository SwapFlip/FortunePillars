package com.marcpg.pillarperil.game.util

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.Registry
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameCompanion
import com.marcpg.pillarperil.map.ArenaMap
import com.marcpg.pillarperil.map.MapBounds
import com.marcpg.pillarperil.map.MapManager
import com.marcpg.pillarperil.map.MapPaster
import com.marcpg.pillarperil.map.SchematicReader
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.Ticking
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
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

    private var countdownStart = 0L

    private var arenaMap: ArenaMap? = null
    private var arenaBounds: MapBounds? = null
    private var lastArenaMap: String? = null

    fun recordVote(player: Player, mode: String? = null, type: String? = null, time: Int? = null) {
        val previous = votes[player.uniqueId] ?: Vote()
        votes[player.uniqueId] = Vote(mode ?: previous.mode, type ?: previous.type, time ?: previous.time)
    }

    fun modeVoteCounts(): Map<String, Int> = votes.values.mapNotNull { it.mode }.groupingBy { it }.eachCount()
    fun typeVoteCounts(): Map<String, Int> = votes.values.mapNotNull { it.type }.groupingBy { it }.eachCount()
    fun timeVoteCounts(): Map<Int, Int> = votes.values.mapNotNull { it.time }.groupingBy { it }.eachCount()

    fun add(player: Player) {
        if (!Configuration.queueEnabled || player in queue || GameManager.isInGame(player)) return

        if (queue.isEmpty())
            loadArena()

        queue.addLast(player)

        val map = arenaMap
        val world = Cage.ensureQueueWorld()
        if (map != null && world != null && map.spawns.isNotEmpty()) {
            val spawn = map.spawns[(queue.size - 1) % map.spawns.size]
            Cage.arena(player, spawn.toLocation(world))
        } else {
            Cage.lobby(player, queue.size - 1, queue.size)
        }
    }

    fun remove(player: Player) {
        if (!Configuration.queueEnabled) return

        queue.remove(player)
        votes.remove(player.uniqueId)
        Cage.clear(player)

        if (queue.size < Configuration.queueMinPlayers)
            countdownStart = 0L
    }

    override fun tick(tick: Ticking.Tick) {
        if (!Configuration.queueEnabled) return

        if (queue.size >= Configuration.queueMinPlayers) {
            if (countdownStart == 0L)
                countdownStart = tick.number.toLong()

            val secondsLeft = (countdownStart + Configuration.queueStartDelay * 20L - tick.number.toLong()) / 20
            if (secondsLeft <= 0) {
                countdownStart = 0L
                check()
            } else {
                queue.forEach {
                    it.sendActionBar(MiniMessage.miniMessage().deserialize("<gradient:${GREEN_COLORS}:$phase>${it.locale().string("queue.countdown", secondsLeft.toString())}</gradient>"))
                }
            }
        } else {
            countdownStart = 0L
            queue.forEach { it.sendActionBar(MiniMessage.miniMessage().deserialize("<gradient:${RED_COLORS}:$phase>${it.locale().string("queue.actionbar", queue.size.toString(), Configuration.queueMinPlayers.toString())}</gradient>")) }
        }
    }

    private fun loadArena() {
        val world = Cage.ensureQueueWorld() ?: return
        val map = MapManager.pickMap(Configuration.queueMinPlayers, world)
        arenaMap = map
        arenaBounds = null

        if (map == null) {
            lastArenaMap = null
            return
        }

        val schematic = SchematicReader.read(MapManager.schematicFile(map.name))
        if (schematic == null) {
            arenaMap = null
            lastArenaMap = null
            PillarPeril.LOG.warn("[Queue] No saved schematic for map \"${map.name}\", falling back to the default lobby.")
            return
        }

        if (lastArenaMap != map.name)
            arenaBounds?.let { clearArea(world, it) }

        lastArenaMap = map.name
        arenaBounds = MapPaster.paste(schematic, world, map.origin)
    }

    private fun clearArea(world: World, bounds: MapBounds) {
        for (x in bounds.minX..bounds.maxX) {
            for (y in bounds.minY..bounds.maxY) {
                for (z in bounds.minZ..bounds.maxZ) {
                    val block = world.getBlockAt(x, y, z)
                    if (block.type != Material.AIR)
                        block.type = Material.AIR
                }
            }
        }
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

        val map = arenaMap
        val location = map?.originLocation(world) ?: Configuration.queueLocation(world)

        placeholders += mapOf(
            "world" to location.world.name,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
        )
        Configuration.queuePostCommands.forEach { PillarPeril.sendCommand(it(placeholders)) }

        val game = mode.constructGame(id, location, players, listOf())
        game.map = map
        game.arenaBounds = arenaBounds
        game.modifiers = listOfNotNull(Registry.modifiers[typeName]?.constructModifier(game))
        game.customItemCountdown = { if (typeName == "speedrunner") 2L else itemTime.toLong() }

        game.init()
    }
}
