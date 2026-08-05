package com.marcpg.pillarperil.game.util

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
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
import com.marcpg.pillarperil.util.playSoundSafe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

object QueueManager : Ticking {
    const val RED_COLORS = "#CC2222:#FF8888"
    const val GREEN_COLORS = "#22CC22:#88FF88"

    private const val VOTE_LOCK_SECONDS = 5
    private val ANNOUNCE_SECONDS = setOf(60L, 30L, 15L, 5L, 4L, 3L, 2L, 1L)

    data class Vote(val mode: String? = null, val type: String? = null, val time: Int? = null, val map: String? = null)

    val queue = ArrayDeque<Player>()

    private val votes = mutableMapOf<UUID, Vote>()

    private var phase = 0.0

    private var countdownStart = 0L
    private var countdownDelay = 0

    private var arenaMap: ArenaMap? = null
    private var arenaBounds: MapBounds? = null
    private var lastArenaMap: String? = null

    // The seconds left until the game starts, or null if no countdown is running. Used to lock voting.
    val countdownSecondsLeft: Int?
        get() = if (countdownStart == 0L) null
        else ((countdownStart + countdownDelay * 20L - Bukkit.getCurrentTick()) / 20).toInt().coerceAtLeast(0)

    val votingLocked: Boolean get() = countdownSecondsLeft?.let { it <= VOTE_LOCK_SECONDS } ?: false

    private fun currentStartDelay(): Int = when {
        queue.size >= Configuration.queueMaxPlayers -> Configuration.queueStartDelayFull
        queue.size * 2 >= Configuration.queueMaxPlayers -> Configuration.queueStartDelayHalf
        else -> Configuration.queueStartDelay
    }

    fun mapVoteCandidates(): List<ArenaMap> {
        val world = Cage.ensureQueueWorld() ?: return emptyList()
        val pool = if (Configuration.queueMapPool.isEmpty())
            MapManager.maps.values
        else
            MapManager.maps.values.filter { it.name in Configuration.queueMapPool }

        return pool.filter { it.world == world.name && it.spawns.size >= Configuration.queueMinPlayers && MapManager.schematicFile(it.name).exists() }
            .sortedBy { it.name }
    }

    fun recordVote(player: Player, mode: String? = null, type: String? = null, time: Int? = null, map: String? = null) {
        val previous = votes[player.uniqueId] ?: Vote()
        votes[player.uniqueId] = Vote(mode ?: previous.mode, type ?: previous.type, time ?: previous.time, map ?: previous.map)
    }

    fun modeVoteCounts(): Map<String, Int> = votes.values.mapNotNull { it.mode }.groupingBy { it }.eachCount()
    fun typeVoteCounts(): Map<String, Int> = votes.values.mapNotNull { it.type }.groupingBy { it }.eachCount()
    fun timeVoteCounts(): Map<Int, Int> = votes.values.mapNotNull { it.time }.groupingBy { it }.eachCount()
    fun mapVoteCounts(): Map<String, Int> = votes.values.mapNotNull { it.map }.groupingBy { it }.eachCount()

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

        // More players in the queue means a shorter countdown. Restart it if the queue filled up.
        val delay = currentStartDelay()
        if (queue.size >= Configuration.queueMinPlayers && (countdownDelay == 0 || delay < countdownDelay)) {
            countdownDelay = delay
            countdownStart = Bukkit.getCurrentTick().toLong()
        }
    }

    fun remove(player: Player) {
        if (!Configuration.queueEnabled) return

        queue.remove(player)
        votes.remove(player.uniqueId)
        Cage.clear(player)

        if (queue.size < Configuration.queueMinPlayers) {
            countdownStart = 0L
            countdownDelay = 0
        }
    }

    override fun tick(tick: Ticking.Tick) {
        if (!Configuration.queueEnabled) return

        if (queue.size >= Configuration.queueMinPlayers) {
            val delay = currentStartDelay()
            if (countdownStart == 0L) {
                countdownStart = tick.number.toLong()
                countdownDelay = delay
            } else if (delay < countdownDelay) {
                countdownDelay = delay
                countdownStart = tick.number.toLong()
            }

            val secondsLeft = (countdownStart + countdownDelay * 20L - tick.number.toLong()) / 20
            if (secondsLeft <= 0) {
                countdownStart = 0L
                countdownDelay = 0
                check()
            } else {
                queue.forEach { p ->
                    p.exp = (secondsLeft.toFloat() / countdownDelay).coerceIn(0.0f, 1.0f)
                    p.level = secondsLeft.toInt()
                    p.sendActionBar(MiniMessage.miniMessage().deserialize("<gradient:${GREEN_COLORS}:$phase>${p.locale().string("queue.countdown", secondsLeft.toString())}</gradient>"))
                }

                if (secondsLeft in ANNOUNCE_SECONDS) {
                    queue.forEach { p ->
                        p.showTitle(Title.title(
                            p.locale().component("queue.countdown", secondsLeft.toString(), color = NamedTextColor.GOLD),
                            Component.empty()
                        ))
                    }
                    queue.forEach { it.playSoundSafe(Sound.UI_BUTTON_CLICK, 1.0f, if (secondsLeft <= 5) 1.0f else 1.5f) }
                }
            }
        } else {
            countdownStart = 0L
            countdownDelay = 0
            queue.forEach { p ->
                p.exp = 0.0f
                p.level = 0
                p.sendActionBar(MiniMessage.miniMessage().deserialize("<gradient:${RED_COLORS}:$phase>${p.locale().string("queue.actionbar", queue.size.toString(), Configuration.queueMinPlayers.toString())}</gradient>"))
            }
        }
    }

    private fun loadArena() {
        val world = Cage.ensureQueueWorld() ?: run { arenaMap = null; return }
        val map = MapManager.pickMap(Configuration.queueMinPlayers, world, lastArenaMap)
        arenaMap = null
        lastArenaMap = null

        if (map == null)
            return

        if (!pasteMap(map))
            PillarPeril.LOG.warn("[Queue] No saved schematic for map \"${map.name}\", falling back to the default lobby.")
    }

    private fun pasteMap(map: ArenaMap): Boolean {
        val world = Cage.ensureQueueWorld() ?: return false
        val schematic = SchematicReader.read(MapManager.schematicFile(map.name)) ?: return false

        if (lastArenaMap != map.name)
            arenaBounds?.let { clearArea(world, it) }

        lastArenaMap = map.name
        arenaMap = map
        arenaBounds = MapPaster.paste(schematic, world, map.origin)
        return true
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

    // Picks the most voted option; ties are broken randomly instead of always being won by the first option.
    private fun <T> List<T>.mostVoted(default: T): T {
        val counts = groupingBy { it }.eachCount()
        val max = counts.values.maxOrNull() ?: return default
        return counts.filterValues { it == max }.keys.random()
    }

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

        val map = applyMapVote(players, votesList.mapNotNull { it.map }.mostVoted(arenaMap?.name ?: "-"))

        startGame(players, mode, typeName, itemTime, map)
    }

    // Re-pastes the queue arena with the most voted map and re-cages the queued players onto it.
    private fun applyMapVote(players: List<Player>, votedName: String): ArenaMap? {
        val voted = MapManager.maps[votedName]
        val current = arenaMap
        if (voted == null || voted == current) return current

        val world = Cage.ensureQueueWorld()
        if (world == null || voted.world != world.name || voted.spawns.size < players.size) return current

        if (!pasteMap(voted)) return current

        players.forEachIndexed { i, p ->
            val spawn = voted.spawns.getOrNull(i) ?: voted.origin
            Cage.arena(p, spawn.toLocation(world))
        }
        return voted
    }

    private fun startGame(players: List<Player>, mode: GameCompanion<*>, typeName: String, itemTime: Int, map: ArenaMap?) {
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

        val location = map?.originLocation(world) ?: Configuration.queueLocation(world)

        players.forEach { p ->
            p.sendMessage(MiniMessage.miniMessage().deserialize(
                p.locale().string("queue.result", mode.gameInfo.namespace, typeName, itemTime.toString(), map?.name ?: "-"))
            )
        }

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