package com.marcpg.pillarperil.game.util

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.Registry
import com.marcpg.pillarperil.event.QueueEvents
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
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
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

    // The item-time options offered in the vote menu. Also used when a "Random" vote wins.
    val TIME_OPTIONS = listOf(3, 5, 10, 15)

    data class Vote(val mode: String? = null, val type: String? = null, val time: Int? = null, val map: String? = null) {
        companion object {
            // Sentinel value a player votes when they pick "Random": if it wins, the category is
            // picked randomly at game start instead of falling back to the configured default.
            const val RANDOM = "__random__"
            const val RANDOM_TIME = Int.MIN_VALUE
        }
    }

    val queue = ArrayDeque<Player>()

    private val votes = mutableMapOf<UUID, Vote>()

    // Pre-game state captured when a player joins the queue, consumed when their game starts.
    private val joinSnapshots = mutableMapOf<UUID, com.marcpg.pillarperil.player.PlayerSnapshot>()

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

    // All maps that could host the queue: in a supported world and with a saved schematic.
    private fun availableMaps(world: World): List<ArenaMap> {
        val pool = if (Configuration.queueMapPool.isEmpty())
            MapManager.maps.values
        else
            MapManager.maps.values.filter { it.name in Configuration.queueMapPool }
        return pool.filter { it.world == world.name && MapManager.schematicFile(it.name).exists() }
            .sortedBy { it.name }
    }

    fun mapVoteCandidates(): List<ArenaMap> {
        val world = Cage.ensureQueueWorld() ?: return emptyList()
        return availableMaps(world).filter { it.spawns.size >= Configuration.queueMinPlayers }
    }

    // If the current arena has fewer cages than players queued, re-paste a bigger map and move
    // everyone already queued onto it, so the spawning can never wrap around and put two players
    // into the same cage.
    private fun growArenaIfNeeded(world: World) {
        val current = arenaMap ?: return
        if (current.spawns.size >= queue.size) return

        val bigger = availableMaps(world)
            .filter { it != current && it.spawns.size >= queue.size }
            .minByOrNull { it.spawns.size } ?: return

        if (!pasteMap(bigger)) return
        queue.forEachIndexed { i, p ->
            val spawn = bigger.spawns.getOrNull(i) ?: bigger.origin
            Cage.arena(p, spawn.toLocation(world))
        }
    }

    // Returns the state the player had before joining the queue, removing it from the pending storage.
    fun consumeJoinSnapshot(player: Player): com.marcpg.pillarperil.player.PlayerSnapshot? =
        joinSnapshots.remove(player.uniqueId)

    fun recordVote(player: Player, mode: String? = null, type: String? = null, time: Int? = null, map: String? = null) {
        val previous = votes[player.uniqueId] ?: Vote()
        votes[player.uniqueId] = Vote(mode ?: previous.mode, type ?: previous.type, time ?: previous.time, map ?: previous.map)
    }

    fun modeVoteCounts(): Map<String, Int> = votes.values.mapNotNull { it.mode }.groupingBy { it }.eachCount()
    fun typeVoteCounts(): Map<String, Int> = votes.values.mapNotNull { it.type }.groupingBy { it }.eachCount()
    fun timeVoteCounts(): Map<Int, Int> = votes.values.mapNotNull { it.time }.groupingBy { it }.eachCount()
    fun mapVoteCounts(): Map<String, Int> = votes.values.mapNotNull { it.map }.groupingBy { it }.eachCount()

    // Number of queued players that voted "Random" for all three categories.
    fun randomVoteCount(): Int = votes.values.count { it.mode == Vote.RANDOM && it.type == Vote.RANDOM && it.time == Vote.RANDOM_TIME }

    fun add(player: Player, preferredMap: ArenaMap? = null) {
        if (!Configuration.queueEnabled || player in queue || GameManager.isInGame(player)) return

        // Capture the player's state before they get caged, so they can be restored to this spot after the game.
        joinSnapshots[player.uniqueId] = com.marcpg.pillarperil.player.PlayerSnapshot(player)

        if (queue.isEmpty()) {
            if (preferredMap == null || !pasteMap(preferredMap))
                loadArena()
        }
        // Bug 11: a newcomer's map pick must NEVER replace the arena under already-queued players.
        // It becomes their vote and is resolved together with everyone else's when the game starts.

        queue.addLast(player)

        // Make sure the arena has enough cages that no spawn wraps around - otherwise two players
        // would be dropped into the same cage (both facing the same interior pillar).
        val world = Cage.ensureQueueWorld()
        if (world != null)
            growArenaIfNeeded(world)

        val map = arenaMap
        if (map != null && world != null && map.spawns.size >= queue.size) {
            val spawn = map.spawns[queue.size - 1]
            Cage.arena(player, spawn.toLocation(world))
        } else {
            // Never wrap the spawn index around: if no bigger arena exists, the newcomer waits in
            // the lobby instead of sharing a cage with a player that joined earlier.
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
        joinSnapshots.remove(player.uniqueId)
        Cage.clear(player)

        if (queue.size < Configuration.queueMinPlayers) {
            countdownStart = 0L
            countdownDelay = 0
        }
    }

    override fun tick(tick: Ticking.Tick) {
        if (!Configuration.queueEnabled) return

        // Keep the map menu in sync with the players clicking it: "Players playing" counts change
        // as games start/finish, so once a second we rebuild every open menu.
        if (tick.number % 20 == 0)
            QueueEvents.refreshMapMenus()

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
                        p.sendActionBar(p.locale().component("queue.countdown", secondsLeft.toString(), color = NamedTextColor.GOLD))
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

        // Wipe the entire arena before pasting: the schematic's own volume plus the play area around
        // the spectator spawn (where lava, water, obsidian, TNT and dropped items can appear), from
        // below the world up - so nothing from a previous game ever survives the map reload.
        val margin = 8
        val playSize = maxOf(
            Configuration.provider.getInt("modifiers.lava-rises.size", 100),
            Configuration.provider.getInt("modifiers.tnt-falls.size", 100),
        )
        val half = playSize / 2
        val spectator = map.spectatorSpawn
        val centerX = spectator?.x ?: map.origin.x
        val centerZ = spectator?.z ?: map.origin.z
        val bounds = MapBounds(
            minOf(map.origin.x, centerX - half) - margin,
            world.minHeight,
            minOf(map.origin.z, centerZ - half) - margin,
            maxOf(map.origin.x + schematic.width, centerX + half) + margin,
            world.maxHeight - 1,
            maxOf(map.origin.z + schematic.length, centerZ + half) + margin,
        )
        clearArea(world, bounds)
        clearEntities(world, bounds)

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

    // Removes every non-player entity inside the wiped area, so a previous game's dropped items,
    // TNT, and mobs can't survive the map reload either.
    private fun clearEntities(world: World, bounds: MapBounds) {
        world.getEntities().forEach { entity ->
            if (entity is Player) return@forEach
            val loc = entity.location
            if (loc.blockX !in bounds.minX..bounds.maxX || loc.blockY !in bounds.minY..bounds.maxY || loc.blockZ !in bounds.minZ..bounds.maxZ)
                return@forEach
            entity.remove()
        }
    }

    // Picks the most voted option; ties are broken randomly instead of always being won by the first option.
    private fun <T> List<T>.mostVoted(default: T): T {
        val counts = groupingBy { it }.eachCount()
        val max = counts.values.maxOrNull() ?: return default
        return counts.filterValues { it == max }.keys.random()
    }

    // Resolves a vote category: if the winner was the "Random" sentinel, an option is picked
    // randomly instead of falling back to the configured default.
    private fun <T> resolveVote(votes: List<T>, sentinel: T, options: List<T>, default: T): T {
        val winner = votes.mostVoted(default)
        return if (winner == sentinel) options.random() else winner
    }

    // Starts the game immediately with everyone currently queued, no matter the player count.
    fun forceStart() {
        if (queue.isEmpty()) return

        countdownStart = 0L
        countdownDelay = 0
        check(force = true)
    }

    private fun check(force: Boolean = false) {
        if (!force && queue.size < Configuration.queueMinPlayers)
            return

        val players = MutableList(queue.size) { queue.removeFirst() }
        val votesList = players.mapNotNull { votes[it.uniqueId] }

        val modeName = resolveVote(votesList.mapNotNull { it.mode }, Vote.RANDOM, Registry.modes.keys.sorted(), Configuration.queueMode.gameInfo.namespace)
        val typeName = resolveVote(votesList.mapNotNull { it.type }, Vote.RANDOM, Registry.modifiers.keys.sorted(), "normal")
        val itemTime = resolveVote(votesList.mapNotNull { it.time }, Vote.RANDOM_TIME, TIME_OPTIONS, Configuration.queueDefaultTime)

        players.forEach { votes.remove(it.uniqueId) }
        val mode = Registry.modes[modeName] ?: Configuration.queueMode

        val mapOptions = mapVoteCandidates().map { it.name }
        val defaultMap = arenaMap?.name ?: "-"
        val mapVotes = votesList.mapNotNull { it.map }
        val votedMap = when {
            // A "Random" vote wins: pick any candidate, or just keep the arena when no maps exist.
            mapVotes.isNotEmpty() && mapVotes.mostVoted(defaultMap) == Vote.RANDOM -> mapOptions.randomOrNull() ?: defaultMap
            else -> mapVotes.mostVoted(defaultMap)
        }

        val map = applyMapVote(players, votedMap)

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
                it.sendMessage(it.locale().component("queue.world_missing", worldName, color = NamedTextColor.RED))
                it.sendMessage(it.locale().component("queue.world_missing.notify", color = NamedTextColor.RED))
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