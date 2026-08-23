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
import com.swapflip.fortunepillars.map.MapBounds
import com.swapflip.fortunepillars.map.MapManager
import com.swapflip.fortunepillars.map.MapPaster
import com.swapflip.fortunepillars.map.SchematicReader
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.FeatureToggle
import com.swapflip.fortunepillars.util.ModifierConfigs
import com.swapflip.fortunepillars.util.QueueMethod
import com.swapflip.fortunepillars.util.Ticking
import com.swapflip.fortunepillars.util.MINI_MESSAGE
import com.swapflip.fortunepillars.util.playSoundSafe
import net.kyori.adventure.text.format.NamedTextColor

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

object QueueManager : Ticking {
    const val RED_COLORS = "#CC2222:#FF8888"
    const val GREEN_COLORS = "#22CC22:#88FF88"

    private const val VOTE_LOCK_SECONDS = 5
    private val ANNOUNCE_SECONDS = setOf(60L, 30L, 15L, 5L, 4L, 3L, 2L, 1L)
    private var warnedWorldFallback = false

    // Ambient queue music: a cached, resolved Sound replayed to everyone queued on a slow loop.
    private var ambientSoundCache: Sound? = null
    private var ambientMusicBroken = false
    private const val AMBIENT_MUSIC_INTERVAL = 600 // 30s

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

    // AUTO-mode players who tried to join while a game was running: their join is deferred until
    // the last game ends (add() rejects mid-match joins), so they are queued instead of being
    // silently dropped until their next server join.
    private val pendingAutoJoins = mutableSetOf<Player>()

    private val votes = mutableMapOf<UUID, Vote>()

    // Pre-game state captured when a player joins the queue, consumed when their game starts.
    private val joinSnapshots = mutableMapOf<UUID, com.swapflip.fortunepillars.player.PlayerSnapshot>()

    private var phase = 0.0
    // Second boundaries the queue displays were last sent on: the countdown and waiting actionbar
    // only change per second, so they are sent once per second instead of every tick.
    private var lastSentSecond = -1
    private var lastWaitSecond = -1

    private var countdownStart = 0L
    private var countdownDelay = 0

    // Set when the last game start failed: the queue stops counting down (so it can't retry the
    // broken start in an endless loop) and waits for a new join or an admin-forced start.
    private var startFailed = false

    private var arenaMap: ArenaMap? = null
    private var arenaBounds: MapBounds? = null
    private var lastArenaMap: String? = null

    // Read-only view of the currently pasted queue arena, used by the queue scoreboard.
    fun currentArenaMap(): ArenaMap? = arenaMap

    // Whether the last game start failed: the queue scoreboard shows a distinct status for it.
    val isStartFailed: Boolean get() = startFailed

    // How many players have cast at least one vote, used by the queue scoreboard.
    fun votesCast(): Int = votes.size

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
        return pool.filter { it.world == world.name && MapManager.hasSchematic(it.name) }
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

    // The map's spawn list is exhausted: the player waits on the arena itself, offset above its
    // top, instead of in the lobby at the world spawn - a lobby-caged overflow player would be
    // stranded at the spawn (0 0 on standard setups) when the game starts on the map.
    private fun overflowSpawn(map: ArenaMap, overflowIndex: Int): BlockPos {
        val base = arenaBounds?.maxY ?: map.origin.y
        return BlockPos(map.origin.x, base + overflowIndex * 3, map.origin.z)
    }

    // Queued players (joining, re-caged on a map vote, or requeued after a failed start) face
    // horizontally towards the spectator spawn, so they watch the arena where the match will be
    // played instead of staring at the void around their cage. The pitch stays level - looking
    // straight at the arena, never up or down at the spawn itself. Without a map there is no
    // spectator spawn to face.
    private fun faceSpectator(player: Player, map: ArenaMap?) {
        val target = map?.spectatorSpawn ?: return
        val from = player.location
        val yaw = Math.toDegrees(atan2(-(target.x + 0.5 - from.x), target.z + 0.5 - from.z)).toFloat()
        player.setRotation(yaw, 0.0f)
    }

    // Returns the state the player had before joining the queue, removing it from the pending storage.
    fun consumeJoinSnapshot(player: Player): com.swapflip.fortunepillars.player.PlayerSnapshot? =
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

        // Admin on/off switch (/pp off): while disabled, only OP players may queue (so an admin can
        // still test the plugin). Stored in data/state.yml so it survives config wipes/restarts.
        if (!FeatureToggle.enabled && !player.isOp) {
            player.sendMessage(player.locale().component("queue.disabled", color = NamedTextColor.RED))
            return
        }

        // The queue locks while a game is running: no mid-match joins, so everyone starts on equal
        // footing and a full server can't get new players dropped into an ongoing game.
        if (GameManager.games.isNotEmpty()) {
            player.sendMessage(player.locale().component("queue.join.in_game", color = NamedTextColor.RED))
            // AUTO mode promises every join is queued automatically: remember the attempt so the
            // player is added as soon as the last game ends, instead of only on their next join.
            if (Configuration.queueMethod == QueueMethod.AUTO)
                pendingAutoJoins += player
            return
        }

        // The queue is capped at `max-players`: a match holds at most that many players, so anyone
        // joining past the cap would never make it into the current match - tell them to wait for
        // the next round instead of silently ignoring the join.
        if (queue.size >= Configuration.queueMaxPlayers) {
            player.sendMessage(player.locale().component("queue.join.full", color = NamedTextColor.RED))
            return
        }

        // A player joining the queue from inside a plugin world (e.g. right after a game, before
        // the delayed send-back fires) would snapshot the game world as their pre-game state, so
        // their restore after the game would strand them there. Send them to the lobby first.
        if (Cage.isPluginWorld(player.world))
            runCatching { player.teleport(Configuration.getLobbySpawn()) }

        // Capture the player's state before they get caged, so they can be restored to this spot after the game.
        joinSnapshots[player.uniqueId] = com.swapflip.fortunepillars.player.PlayerSnapshot(player)

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
        if (map != null && world != null) {
            val spawn = if (map.spawns.size >= queue.size)
                map.spawns[queue.size - 1]
            else
                // No bigger arena exists (growArenaIfNeeded already tried): the newcomer waits on
                // the arena above its top, never in the lobby at the world spawn - otherwise the
                // game would start with them stranded at 0 0 in the void.
                overflowSpawn(map, queue.size - map.spawns.size)
            Cage.arena(player, spawn.toLocation(world))
            faceSpectator(player, map)
        } else {
            // No map at all: the void lobby around the world spawn doubles as the arena (games
            // generate their platforms around the same spot), so the tower is the right place.
            Cage.lobby(player, queue.size - 1, queue.size)
        }

        // More players in the queue means a shorter countdown. Restart it if the queue filled up.
        // A new join also clears a previous start failure: the cause may be gone (new map vote,
        // world restored), so the queue gets one fresh chance to start.
        startFailed = false
        val delay = currentStartDelay()
        if (queue.size >= Configuration.queueMinPlayers && (countdownDelay == 0 || delay < countdownDelay)) {
            countdownDelay = delay
            countdownStart = Bukkit.getCurrentTick().toLong()
        }

        // The queue sidebar follows the player's whole queue stay: shown on join, hidden on leave
        // or when their game starts. Values update themselves on the configured tick interval.
        QueueScoreboards.show(player)
    }

    fun remove(player: Player) {
        // No early return when the queue is disabled: players who were queued before the config
        // flip still have snapshots/votes/cages to clean up, and a quit mid-queue must never
        // strand them (restored items, teleport home, ...). Only players that actually carry queue
        // state are touched though - a regular quit (never queued) must not reset inventory slots.
        val hadQueueState = player in queue || player.uniqueId in votes || player.uniqueId in joinSnapshots || player in pendingAutoJoins

        queue.remove(player)
        pendingAutoJoins.remove(player)
        votes.remove(player.uniqueId)
        // The join snapshot is only applied when the player actually leaves the queue: a game start
        // consumes it first (via consumeJoinSnapshot), so it must not be restored in that case.
        val snapshot = joinSnapshots.remove(player.uniqueId)
        if (hadQueueState) Cage.clear(player)
        // The queue sidebar dies with the queue state - on quit, queue leave or world change alike.
        QueueScoreboards.hide(player)

        // Leaving the queue returns the player to their pre-queue state: their own items, XP and
        // spot - everything the lobby items and queue stats overwrote while waiting. Offline-safe:
        // the teleport is skipped and applied by the rejoin handler when they come back.
        if (snapshot != null) {
            runCatching { snapshot.set(player) }
                .onFailure { FortunePillars.LOG.warn("Could not restore ${player.name}'s state after leaving the queue.", it) }
        }

        if (Configuration.queueEnabled && queue.size < Configuration.queueMinPlayers) {
            countdownStart = 0L
            countdownDelay = 0
        }
    }

    // Resets the countdown state and shows the red "waiting for players" actionbar once per second,
    // used both while a game is running (the queue waits) and while below the minimum player count.
    private fun resetCountdownAndShowWaiting(tickNumber: Int) {
        countdownStart = 0L
        countdownDelay = 0
        lastSentSecond = -1
        val waitSecond = tickNumber / 20
        if (waitSecond != lastWaitSecond) {
            lastWaitSecond = waitSecond
            queue.forEach { p ->
                if (QueueEvents.isLeaving(p)) return@forEach
                p.exp = 0.0f
                p.level = 0
                // Shows progress towards a FULL map (x/max), not just the minimum: "1/8" tells
                // players how close the lobby is to a complete match.
                p.sendActionBar(MINI_MESSAGE.deserialize("<gradient:${RED_COLORS}:$phase>${p.locale().string("queue.actionbar", queue.size.toString(), Configuration.queueMaxPlayers.toString())}</gradient>"))
            }
        }
    }

    // Plays the configured ambient music track to everyone currently queued. The Sound is resolved
    // once and cached; a bad sound name disables the feature instead of spamming the log each loop.
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
        queue.forEach { it.playSoundSafe(sound, Configuration.queueAmbientMusicVolume.toFloat(), 1.0f) }
    }

    override fun tick(tick: Ticking.Tick) {
        if (!Configuration.queueEnabled) return

        // Keep the map menu in sync with the players clicking it: "Players playing" counts change
        // as games start/finish, so once a second we rebuild every open menu.
        if (tick.number % 20 == 0)
            QueueEvents.refreshMapMenus()

        // Soft looping ambient music for the queue, if enabled.
        if (tick.number % AMBIENT_MUSIC_INTERVAL == 0)
            playAmbientMusic()

        // While a game runs, the queue waits: the countdown is frozen and must not fire check().
        if (GameManager.games.isNotEmpty()) {
            resetCountdownAndShowWaiting(tick.number)
            return
        }

        // Deferred AUTO joins: with no game running the queue unlocks again, so joiners who were
        // rejected while a match was in progress are finally added (and caged) like any other join.
        if (pendingAutoJoins.isNotEmpty()) {
            val waiting = pendingAutoJoins.toList()
            pendingAutoJoins.clear()
            waiting.forEach { if (it.isOnline) add(it) }
        }

        // A failed start freezes the countdown: the queue would otherwise retry the broken start
        // in an endless loop, moving the players between arena and void forever.
        if (queue.size >= Configuration.queueMinPlayers && !startFailed) {
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
                lastSentSecond = -1
                check()
            } else {
                // The gradient phase loops 0..1 over 100 ticks (~5s), so the actionbar visibly
                // breathes instead of sitting frozen.
                phase = (tick.number % 100) / 100.0

                // Exp, level and the actionbar only change on second boundaries: sending them
                // every tick is 20x the needed packets for identical content.
                if (secondsLeft != lastSentSecond.toLong()) {
                    lastSentSecond = secondsLeft.toInt()
                    queue.forEach { p ->
                        p.exp = (secondsLeft.toFloat() / countdownDelay).coerceIn(0.0f, 1.0f)
                        p.level = secondsLeft.toInt()
                        p.sendActionBar(MINI_MESSAGE.deserialize("<gradient:${GREEN_COLORS}:$phase>${p.locale().string("queue.countdown", secondsLeft.toString())}</gradient>"))
                    }

                    if (secondsLeft in ANNOUNCE_SECONDS) {
                        queue.forEach { p ->
                            p.sendActionBar(p.locale().component("queue.countdown", secondsLeft.toString(), color = NamedTextColor.GOLD))
                        }
                        queue.forEach { it.playSoundSafe(Sound.UI_BUTTON_CLICK, 1.0f, if (secondsLeft <= 5) 1.0f else 1.5f) }
                    }
                }
            }
        } else {
            resetCountdownAndShowWaiting(tick.number)
        }
    }

    private fun loadArena() {
        val world = Cage.ensureQueueWorld() ?: run { arenaMap = null; return }
        val map = MapManager.pickMap(Configuration.queueMinPlayers, world, lastArenaMap)
        arenaMap = null
        arenaBounds = null // Only ever set again by a successful paste.
        lastArenaMap = null

        if (map == null)
            return

        if (!pasteMap(map))
            FortunePillars.LOG.warn("[Queue] No saved schematic for map \"${map.name}\", falling back to the default lobby.")
    }

    // A paste must never abort the queue tick loop (a schematic with blocks above the world height
    // or a corrupt file would otherwise throw out of check()/loadArena with players drained and
    // stranded): failures are logged and treated as "no map".
    private fun pasteMap(map: ArenaMap): Boolean =
        runCatching { pasteMapUnchecked(map) }
            .onFailure { FortunePillars.LOG.error("[Queue] Could not paste map \"${map.name}\": ${it.javaClass.simpleName}: ${it.message}. The queue keeps its current arena.", it) }
            .getOrDefault(false)

    private fun pasteMapUnchecked(map: ArenaMap): Boolean {
        val world = Cage.ensureQueueWorld() ?: return false
        val schematic = SchematicReader.read(MapManager.schematicFile(map.name)) ?: return false

        if (lastArenaMap != map.name)
            arenaBounds?.let { clearArea(world, it) }

        // Wipe the entire arena before pasting: the schematic's own volume plus the play area around
        // the spectator spawn (where lava, water, obsidian, TNT and dropped items can appear), from
        // below the world up - so nothing from a previous game ever survives the map reload.
        val margin = 8
        val playSize = maxOf(
            ModifierConfigs.int("lava-rises", "size", 100),
            ModifierConfigs.int("tnt-falls", "size", 100),
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
        // Chunk-wise with physics disabled: block changes don't cascade neighbor updates, chunks
        // outside the loaded arena (void margin) are skipped, and getBlock is relative to the chunk
        // instead of a world lookup that can force chunk loads.
        for (cx in (bounds.minX shr 4)..(bounds.maxX shr 4)) {
            for (cz in (bounds.minZ shr 4)..(bounds.maxZ shr 4)) {
                if (!world.isChunkLoaded(cx, cz)) continue
                val chunk = world.getChunkAt(cx, cz)
                val minX = max(bounds.minX, cx * 16)
                val maxX = min(bounds.maxX, cx * 16 + 15)
                val minZ = max(bounds.minZ, cz * 16)
                val maxZ = min(bounds.maxZ, cz * 16 + 15)
                for (x in minX..maxX) {
                    val lx = x and 15
                    for (z in minZ..maxZ) {
                        val lz = z and 15
                        for (y in bounds.minY..bounds.maxY) {
                            val block = chunk.getBlock(lx, y, lz)
                            if (block.type != Material.AIR)
                                block.setType(Material.AIR, false)
                        }
                    }
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

        // An admin force start overrides a previous start failure: the cause may be fixed by now.
        startFailed = false
        countdownStart = 0L
        countdownDelay = 0
        check(force = true)
    }

    private fun check(force: Boolean = false) {
        // While a game is running the queue must not start another one - a forced check (admin
        // command) is still required to work during the cages phase, but never into a running game.
        if (GameManager.games.isNotEmpty())
            return

        if (!force && queue.size < Configuration.queueMinPlayers)
            return

        // Defensive cap: `add()` rejects joiners past max-players, but if the queue was ever
        // inflated beyond the cap (older state, race at force start), the game must not start
        // with more players than a match can hold. Leftovers stay queued for the next match.
        val playerCount = min(queue.size, Configuration.queueMaxPlayers)
        val players = MutableList(playerCount) { queue.removeFirst() }
        val votesList = players.mapNotNull { votes[it.uniqueId] }

        val modeName = resolveVote(votesList.mapNotNull { it.mode }, Vote.RANDOM, Registry.modes.keys.sorted(), Configuration.queueMode.gameInfo.namespace)
        val typeName = resolveVote(votesList.mapNotNull { it.type }, Vote.RANDOM, (Registry.modifiers.keys + "multi").sorted(), "normal")
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

        if (!pasteMap(voted)) {
            // The old arena may already be wiped by the failed paste attempt: players must not
            // stand where the floor was. Tower them at the queue spawn instead and play the game
            // map-less - platforms are generated around the towers, so nobody falls through.
            players.forEachIndexed { i, p -> if (p.isOnline) Cage.lobby(p, i, players.size) }
            return null
        }

        players.forEachIndexed { i, p ->
            val spawn = voted.spawns.getOrNull(i) ?: overflowSpawn(voted, i - voted.spawns.size)
            Cage.arena(p, spawn.toLocation(world))
            faceSpectator(p, voted)
        }
        return voted
    }

    // Puts players back into the queue with their cages after a failed start, so a broken map or
    // missing world never strands them in the plugin world. The cages mirror add()'s placement -
    // arena spawns (or overflow above the arena) when a map exists, floored lobby towers otherwise:
    // never a floorless cage at the world spawn (0 0 on standard setups), where the players would
    // fall through the void and die until the queue finally breaks the loop.
    private fun requeue(players: List<Player>, world: World?) {
        players.forEachIndexed { i, p ->
            if (!p.isOnline) return@forEachIndexed
            if (p !in queue)
                queue.addLast(p)
            val map = arenaMap
            if (map != null && world != null) {
                val spawn = map.spawns.getOrNull(i) ?: overflowSpawn(map, i - map.spawns.size)
                Cage.arena(p, spawn.toLocation(world))
                faceSpectator(p, map)
            } else {
                Cage.lobby(p, i, players.size)
            }
            // init() consumes join snapshots as it creates its players: re-capture the state so a
            // later queue leave still restores the player instead of silently dropping it.
            if (p.uniqueId !in joinSnapshots) {
                p.sendMessage(p.locale().component("queue.start_failed", color = NamedTextColor.RED))
                joinSnapshots[p.uniqueId] = com.swapflip.fortunepillars.player.PlayerSnapshot(p)
            }
            // The failed start hid the queue sidebar; back in the queue it comes right back.
            QueueScoreboards.show(p)
        }
        // A failed start must not retry on its own in an endless loop: the countdown is frozen
        // until a new player joins or an admin forces a start.
        startFailed = true
        countdownStart = 0L
        countdownDelay = 0
        lastSentSecond = -1
    }

    private fun startGame(players: List<Player>, mode: GameCompanion<*>, typeName: String, itemTime: Int, map: ArenaMap?) {
        val id = Game.generateId()
        val placeholders = mutableMapOf(
            "id" to id,
            "mode" to mode.gameInfo.namespace,
            "players" to players.size,
        )

        // The world must be resolved BEFORE any queue/cage state is mutated: on a misconfigured
        // server the start is aborted here and the players stay queued instead of being stranded.
        // Reuse the exact world the queue already created/resolved (Cage.queueWorldName) so a
        // placeholder-based config can never resolve to two different worlds between queue setup and
        // game start. Only re-expand placeholders when the queue couldn't pre-create a world.
        val worldName = Cage.queueWorldName ?: Configuration.queueWorldName(placeholders)
        // Pre-rename installs keep their world under the "PillarPeril" name: if the configured world
        // doesn't exist, fall back to the existing PillarPeril world. Games never create new worlds -
        // they are only ever played in a world an admin actually built, so a missing world aborts the
        // start instead of generating a fresh void world. The hidden console note is logged only once -
        // every game start re-resolves the same missing world otherwise and spams the console.
        val world = Bukkit.getWorld(worldName)
            ?: Bukkit.getWorld("PillarPeril")?.also {
                if (!warnedWorldFallback) {
                    FortunePillars.LOG.info("Game world \"$worldName\" does not exist; using the existing \"PillarPeril\" world instead.")
                    warnedWorldFallback = true
                }
            }
        if (world == null) {
            requeue(players, Cage.ensureQueueWorld())
            players.forEach {
                it.sendMessage(it.locale().component("queue.world_missing", worldName, color = NamedTextColor.RED))
                it.sendMessage(it.locale().component("queue.world_missing.notify", color = NamedTextColor.RED))
            }
            return
        }

        // The game brings its own scoreboard: the queue sidebar must be gone before init, or the
        // two displays would fight over the player's sidebar.
        players.forEach { QueueScoreboards.hide(it) }

        Cage.clearAll(players)
        Cage.clearTowers()

        Configuration.queuePreCommands.forEach { FortunePillars.sendCommand(it(placeholders)) }

        val location = map?.originLocation(world) ?: Configuration.queueLocation(world)

        players.forEach { p ->
            p.sendMessage(MINI_MESSAGE.deserialize(
                p.locale().string("queue.result", mode.gameInfo.namespace, typeName, itemTime.toString(), map?.name ?: "-"))
            )
        }

        placeholders += mapOf(
            "world" to location.world.name,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
        )
        Configuration.queuePostCommands.forEach { FortunePillars.sendCommand(it(placeholders)) }

        // A throw inside init() (bad schematic, spawn error, modifier init) must not leave the
        // players drained, uncaged and stranded: put them back into the queue.
        runCatching {
            val game = mode.constructGame(id, location, players, listOf())
            game.map = map
            // Only map games have meaningful arena bounds: assigning the queue's stale bounds to a
            // map-less game would aim every hazard, border and sweep at the wrong coordinates.
            game.arenaBounds = if (map != null) arenaBounds else null
            // "Multi" is a meta option: it is not a modifier itself, it opens a picker at match start
            // where every player selects their own modifiers, and every option with at least one vote
            // gets activated. Every other type maps to exactly one modifier as before.
            if (typeName == "multi") {
                game.multiSelect = true
                game.modifiers = emptyList()
            } else {
                game.modifiers = listOfNotNull(Registry.modifiers[typeName]?.constructModifier(game))
            }
            game.customItemCountdown = { itemTime.toLong() }

            game.init()
        }.onFailure {
            FortunePillars.LOG.error("Could not start the queued game (mode ${mode.gameInfo.namespace}, map ${map?.name ?: "-"}): players are back in the queue.", it)
            requeue(players, world)
        }
    }
}
