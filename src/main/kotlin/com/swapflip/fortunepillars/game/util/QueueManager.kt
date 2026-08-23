package com.swapflip.fortunepillars.game.util

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.Registry
import com.swapflip.fortunepillars.event.QueueEvents
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameCompanion
import com.swapflip.fortunepillars.map.ArenaMap
import com.swapflip.fortunepillars.map.BlockPos
import com.swapflip.fortunepillars.map.MapManager
import com.swapflip.fortunepillars.map.MapPaster
import com.swapflip.fortunepillars.map.SchematicReader
import com.swapflip.fortunepillars.map.translateMapToOrigin
import com.swapflip.fortunepillars.player.PlayerSnapshot
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.FeatureToggle
import com.swapflip.fortunepillars.util.MINI_MESSAGE
import com.swapflip.fortunepillars.util.QueueMethod
import com.swapflip.fortunepillars.util.Ticking
import com.swapflip.fortunepillars.util.WorldManager
import com.swapflip.fortunepillars.util.playSoundSafe
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.min

object QueueManager : Ticking {
    const val RED_COLORS = "#CC2222:#FF8888"
    const val GREEN_COLORS = "#22CC22:#88FF88"

    private const val VOTE_LOCK_SECONDS = 5
    private val ANNOUNCE_SECONDS = setOf(60L, 30L, 15L, 5L, 4L, 3L, 2L, 1L)
    private var warnedWorldFallback = false

    private var ambientSoundCache: Sound? = null
    private var ambientMusicBroken = false
    private const val AMBIENT_MUSIC_INTERVAL = 600

    val TIME_OPTIONS = listOf(3, 5, 10, 15)

    data class Vote(val mode: String? = null, val type: String? = null, val time: Int? = null, val map: String? = null) {
        companion object {
            const val RANDOM = "__random__"
            const val RANDOM_TIME = Int.MIN_VALUE
        }
    }

    // One queue per map that currently has waiting players, keyed by map name.
    private val mapQueues = mutableMapOf<String, MapQueue>()

    // AUTO-mode players deferred because a game was running when they tried to join: player -> mapName.
    private val pendingAutoJoins = mutableMapOf<Player, String>()

    // Last map a player selected, used for AUTO re-join and as the default map.
    private var lastMap: String? = null

    private var phase = 0.0
    private var lastSentSecond = -1
    private var lastWaitSecond = -1

    private val gameIdCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // ---- lookups ----
    fun currentQueueOf(player: Player): MapQueue? = mapQueues.values.firstOrNull { player in it.players }
    fun queueForMap(mapName: String): MapQueue? = mapQueues[mapName]
    private fun getOrCreateQueue(map: ArenaMap): MapQueue = mapQueues.getOrPut(map.name) { MapQueue(map) }

    // Maps that can host a queue: have a saved schematic and enough spawns for the minimum.
    fun availableMaps(): List<ArenaMap> {
        val pool = if (Configuration.queueMapPool.isEmpty()) MapManager.maps.values
                   else MapManager.maps.values.filter { it.name in Configuration.queueMapPool }
        val filtered = pool.filter { MapManager.hasSchematic(it.name) && it.spawns.size >= Configuration.queueMinPlayers }
        return if (Configuration.perGameWorlds) filtered.sortedBy { it.name }
            else filtered.filter { it.world == (Cage.queueWorldName ?: "PillarPeril") }.sortedBy { it.name }
    }

    fun consumeJoinSnapshot(player: Player): PlayerSnapshot? =
        mapQueues.values.firstNotNullOfOrNull { it.snapshots.remove(player.uniqueId) }

    // ---- voting (per current queue) ----
    fun recordVote(player: Player, mode: String? = null, type: String? = null, time: Int? = null) {
        val q = currentQueueOf(player) ?: return
        val prev = q.votes[player.uniqueId] ?: Vote()
        q.votes[player.uniqueId] = Vote(mode ?: prev.mode, type ?: prev.type, time ?: prev.time, null)
    }

    fun modeVoteCounts(queue: MapQueue): Map<String, Int> = queue.votes.values.mapNotNull { it.mode }.groupingBy { it }.eachCount()
    fun typeVoteCounts(queue: MapQueue): Map<String, Int> = queue.votes.values.mapNotNull { it.type }.groupingBy { it }.eachCount()
    fun timeVoteCounts(queue: MapQueue): Map<Int, Int> = queue.votes.values.mapNotNull { it.time }.groupingBy { it }.eachCount()
    fun votesCast(queue: MapQueue): Int = queue.votes.size
    val isStartFailed: Boolean get() = mapQueues.values.any { it.startFailed }

    fun countdownSecondsLeft(queue: MapQueue): Int? =
        if (queue.countdownStart == 0L) null
        else ((queue.countdownStart + queue.countdownDelay * 20L - Bukkit.getCurrentTick()) / 20).toInt().coerceAtLeast(0)

    fun votingLocked(queue: MapQueue): Boolean = countdownSecondsLeft(queue)?.let { it <= VOTE_LOCK_SECONDS } ?: false

    private fun currentStartDelay(size: Int): Int = when {
        size >= Configuration.queueMaxPlayers -> Configuration.queueStartDelayFull
        size * 2 >= Configuration.queueMaxPlayers -> Configuration.queueStartDelayHalf
        else -> Configuration.queueStartDelay
    }

    // ---- join / leave ----
    fun joinMap(player: Player, mapName: String) {
        if (!Configuration.queueEnabled || GameManager.isInGame(player)) return
        if (!FeatureToggle.enabled && !player.isOp) {
            player.sendMessage(player.locale().component("queue.disabled", color = NamedTextColor.RED))
            return
        }
        if (GameManager.games.size >= Configuration.maxConcurrentGames) {
            if (Configuration.queueMethod == QueueMethod.AUTO) pendingAutoJoins[player] = mapName
            player.sendMessage(player.locale().component("queue.join.full", color = NamedTextColor.RED))
            return
        }
        val target = mapName.takeIf { it.isNotEmpty() }
            ?: lastMap?.takeIf { it.isNotEmpty() }
            ?: run { if (Configuration.queueMethod == QueueMethod.AUTO) availableMaps().firstOrNull()?.name else null }
            ?: run {
            player.sendMessage(player.locale().component("queue.join.no_map", color = NamedTextColor.RED))
            return
        }
        val map = MapManager.maps[target] ?: run {
            player.sendMessage(player.locale().component("queue.join.invalid_map", color = NamedTextColor.RED))
            return
        }
        if (map.spawns.size < Configuration.queueMinPlayers) {
            player.sendMessage(player.locale().component("queue.join.too_small", color = NamedTextColor.RED))
            return
        }
        if (player in (currentQueueOf(player)?.players ?: emptyList())) return

        lastMap = target
        val queue = getOrCreateQueue(map)
        if (Cage.isPluginWorld(player.world))
            runCatching { player.teleport(Configuration.getLobbySpawn()) }

        queue.snapshots[player.uniqueId] = PlayerSnapshot(player)
        queue.players.add(player)
        Cage.lobby(player, queue.players.size - 1, queue.players.size)

        queue.startFailed = false
        val delay = currentStartDelay(queue.players.size)
        if (queue.players.size >= Configuration.queueMinPlayers && (queue.countdownDelay == 0 || delay < queue.countdownDelay)) {
            queue.countdownDelay = delay
            queue.countdownStart = Bukkit.getCurrentTick().toLong()
        }
        QueueScoreboards.show(player)
    }

    fun leaveQueue(player: Player) {
        val queue = currentQueueOf(player) ?: return
        queue.players.remove(player)
        queue.votes.remove(player.uniqueId)
        val snapshot = queue.snapshots.remove(player.uniqueId)
        Cage.clear(player)
        QueueScoreboards.hide(player)
        if (snapshot != null)
            runCatching { snapshot.set(player) }
                .onFailure { FortunePillars.LOG.warn("Could not restore ${player.name}'s state after leaving the queue.", it) }
        if (queue.players.isEmpty())
            mapQueues.remove(queue.map.name)
        else if (queue.players.size < Configuration.queueMinPlayers) {
            queue.countdownStart = 0L
            queue.countdownDelay = 0
        }
    }

    // ---- ticking ----
    override fun tick(tick: Ticking.Tick) {
        if (!Configuration.queueEnabled) return
        if (tick.number % 20 == 0) QueueEvents.refreshMapMenus()
        if (tick.number % AMBIENT_MUSIC_INTERVAL == 0) playAmbientMusic()

        if (pendingAutoJoins.isNotEmpty()) {
            val waiting = pendingAutoJoins.toList()
            pendingAutoJoins.clear()
            waiting.forEach { (p, map) -> if (p.isOnline) joinMap(p, map) }
        }

        mapQueues.values.toList().forEach { tickQueue(it, tick) }
    }

    private fun tickQueue(queue: MapQueue, tick: Ticking.Tick) {
        val size = queue.players.size
        val canStart = size >= Configuration.queueMinPlayers && !queue.startFailed
            && GameManager.games.size < Configuration.maxConcurrentGames
        if (canStart) {
            if (queue.countdownStart == 0L) {
                queue.countdownStart = tick.number.toLong()
                queue.countdownDelay = currentStartDelay(size)
            } else if (currentStartDelay(size) < queue.countdownDelay) {
                queue.countdownDelay = currentStartDelay(size)
                queue.countdownStart = tick.number.toLong()
            }
            val secondsLeft = (queue.countdownStart + queue.countdownDelay * 20L - tick.number.toLong()) / 20
            if (secondsLeft <= 0) {
                queue.countdownStart = 0L; queue.countdownDelay = 0; lastSentSecond = -1
                check(queue)
            } else {
                phase = (tick.number % 100) / 100.0
                if (secondsLeft != lastSentSecond.toLong()) {
                    lastSentSecond = secondsLeft.toInt()
                    queue.players.forEach { p ->
                        p.exp = (secondsLeft.toFloat() / queue.countdownDelay).coerceIn(0.0f, 1.0f)
                        p.level = secondsLeft.toInt()
                        p.sendActionBar(MINI_MESSAGE.deserialize("<gradient:${GREEN_COLORS}:$phase>${p.locale().string("queue.countdown", secondsLeft.toString())}</gradient>"))
                    }
                    if (secondsLeft in ANNOUNCE_SECONDS) {
                        queue.players.forEach { p ->
                            p.sendActionBar(p.locale().component("queue.countdown", secondsLeft.toString(), color = NamedTextColor.GOLD))
                            p.playSoundSafe(Sound.UI_BUTTON_CLICK, 1.0f, if (secondsLeft <= 5) 1.0f else 1.5f)
                        }
                    }
                }
            }
        } else {
            resetWaiting(queue, tick.number)
        }
    }

    private fun resetWaiting(queue: MapQueue, tickNumber: Int) {
        queue.countdownStart = 0L
        queue.countdownDelay = 0
        lastSentSecond = -1
        val waitSecond = tickNumber / 20
        if (waitSecond != lastWaitSecond) {
            lastWaitSecond = waitSecond
            queue.players.forEach { p ->
                if (QueueEvents.isLeaving(p)) return@forEach
                p.exp = 0.0f
                p.level = 0
                val reason = if (GameManager.games.size >= Configuration.maxConcurrentGames) "queue.wait.full_games" else "queue.actionbar"
                p.sendActionBar(MINI_MESSAGE.deserialize("<gradient:${RED_COLORS}:$phase>${p.locale().string(reason, queue.players.size.toString(), Configuration.queueMaxPlayers.toString())}</gradient>"))
            }
        }
    }

    private fun playAmbientMusic() {
        if (!Configuration.queueAmbientMusic || ambientMusicBroken) return
        if (ambientSoundCache == null) {
            ambientSoundCache = try {
                Sound.valueOf(Configuration.queueAmbientMusicSound.uppercase())
            } catch (e: Exception) {
                FortunePillars.LOG.warn("Invalid queue.ambient-music.sound '${Configuration.queueAmbientMusicSound}' - ambient music disabled.", e)
                ambientMusicBroken = true
                return
            }
        }
        val sound = ambientSoundCache ?: return
        mapQueues.values.forEach { it.players.forEach { p -> p.playSoundSafe(sound, Configuration.queueAmbientMusicVolume.toFloat(), 1.0f) } }
    }

    // ---- start ----
    private fun check(queue: MapQueue, force: Boolean = false) {
        if (GameManager.games.size >= Configuration.maxConcurrentGames) return
        if (!force && queue.players.size < Configuration.queueMinPlayers) return

        val playerCount = min(queue.players.size, Configuration.queueMaxPlayers)
        val players = MutableList(playerCount) { queue.players.removeFirst() }
        val votesList = players.mapNotNull { queue.votes[it.uniqueId] }

        val modeName = resolveVote(votesList.mapNotNull { it.mode }, Vote.RANDOM, Registry.modes.keys.sorted(), Configuration.queueMode.gameInfo.namespace)
        val typeName = resolveVote(votesList.mapNotNull { it.type }, Vote.RANDOM, (Registry.modifiers.keys + "multi").sorted(), "normal")
        val itemTime = resolveVote(votesList.mapNotNull { it.time }, Vote.RANDOM_TIME, TIME_OPTIONS, Configuration.queueDefaultTime)

        players.forEach { queue.votes.remove(it.uniqueId) }
        val mode = Registry.modes[modeName] ?: Configuration.queueMode
        startGame(queue, players, mode, typeName, itemTime)
    }

    private fun startGame(queue: MapQueue, players: List<Player>, mode: GameCompanion<*>, typeName: String, itemTime: Int) {
        val id = Game.generateId()
        val idInt = gameIdCounter.incrementAndGet()
        val placeholders = mutableMapOf("id" to id, "mode" to mode.gameInfo.namespace, "players" to players.size)

        // Resolve the world: a fresh per-game world, or the shared world when per-game-worlds is off.
        val (gameWorld, gameMap) = if (Configuration.perGameWorlds) {
            val w = WorldManager.createGameWorld(idInt) ?: run {
                requeue(players, queue)
                players.forEach { it.sendMessage(it.locale().component("queue.world_missing", WorldManager.gameWorldName(idInt), color = NamedTextColor.RED)) }
                return
            }
            w to translateMapToOrigin(queue.map)
        } else {
            val name = Cage.queueWorldName ?: Configuration.queueWorldName(placeholders)
            val w = Bukkit.getWorld(name) ?: Bukkit.getWorld("PillarPeril")?.also {
                if (!warnedWorldFallback) { FortunePillars.LOG.info("Game world \"$name\" does not exist; using the existing \"PillarPeril\" world instead."); warnedWorldFallback = true }
            } ?: run {
                requeue(players, queue)
                players.forEach { it.sendMessage(it.locale().component("queue.world_missing", name, color = NamedTextColor.RED)) }
                return
            }
            w to queue.map // legacy: paste at the map's original origin in the shared world
        }

        players.forEach { QueueScoreboards.hide(it) }
        Cage.clearAll(players)
        Cage.clearTowers(queue.players)

        Configuration.queuePreCommands.forEach { FortunePillars.sendCommand(it(placeholders)) }

        val schematic = MapManager.schematicFile(gameMap.name)?.let { SchematicReader.read(it) }
        if (schematic == null) {
            requeue(players, queue)
            players.forEach { it.sendMessage(it.locale().component("queue.schematic_missing", gameMap.name, color = NamedTextColor.RED)) }
            return
        }
        val arenaBounds = MapPaster.paste(schematic, gameWorld, gameMap.origin)

        players.forEachIndexed { i, p ->
            val spawn = gameMap.spawns.getOrNull(i) ?: BlockPos(0, (arenaBounds?.maxY ?: gameMap.origin.y) + i * 3, 0)
            p.teleport(spawn.toLocation(gameWorld))
        }

        players.forEach { p ->
            p.sendMessage(MINI_MESSAGE.deserialize(p.locale().string("queue.result", mode.gameInfo.namespace, typeName, itemTime.toString(), gameMap.name)))
        }

        placeholders += mapOf("world" to gameWorld.name, "x" to gameMap.origin.x, "y" to gameMap.origin.y, "z" to gameMap.origin.z)
        Configuration.queuePostCommands.forEach { FortunePillars.sendCommand(it(placeholders)) }

        runCatching {
            val game = mode.constructGame(id, gameMap.originLocation(gameWorld), players, listOf())
            game.map = gameMap
            game.arenaBounds = arenaBounds
            if (typeName == "multi") {
                game.multiSelect = true
                game.modifiers = emptyList()
            } else {
                game.modifiers = listOfNotNull(Registry.modifiers[typeName]?.constructModifier(game))
            }
            game.customItemCountdown = { itemTime.toLong() }
            game.init()
        }.onFailure {
            FortunePillars.LOG.error("Could not start game on map ${gameMap.name}: players are back in the queue.", it)
            requeue(players, queue)
        }
    }

    private fun requeue(players: List<Player>, queue: MapQueue) {
        players.forEach { p ->
            if (!p.isOnline) return@forEach
            if (p !in queue.players) queue.players.addLast(p)
            Cage.lobby(p, queue.players.size - 1, queue.players.size)
            if (p.uniqueId !in queue.snapshots) {
                p.sendMessage(p.locale().component("queue.start_failed", color = NamedTextColor.RED))
                queue.snapshots[p.uniqueId] = PlayerSnapshot(p)
            }
            QueueScoreboards.show(p)
        }
        queue.startFailed = true
        queue.countdownStart = 0L; queue.countdownDelay = 0; lastSentSecond = -1
    }

    fun forceStart(mapName: String? = null) {
        val queue = (mapName?.let { mapQueues[it] } ?: mapQueues.values.firstOrNull { it.players.isNotEmpty() }) ?: return
        queue.startFailed = false
        queue.countdownStart = 0L; queue.countdownDelay = 0
        check(queue, force = true)
    }
}
