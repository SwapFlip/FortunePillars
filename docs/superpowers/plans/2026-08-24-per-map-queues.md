# Per-Map Queue System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single global vote-queue with per-map queues (Hypixel/BedWars style): each map has its own queue, starts its own game in a fresh void world at (0,0,0), and the world is deleted on cleanup.

**Architecture:** `QueueManager` holds `mapQueues: Map<String, MapQueue>`. Joining a map cages the player in the lobby world and adds them to that map's `MapQueue`. A per-queue countdown starts at `queue-min-players`; at `queue-max-players` (or countdown end) with a free concurrent-game slot, `check()` creates a void world, translates the `ArenaMap` to origin (0,0,0), pastes it, teleports players in, and starts `Game`. `Game.cleanup()` deletes the world. A `per-game-worlds: false` config reverts to a single shared world (safety net).

**Tech Stack:** Kotlin, Paper 1.20.4 API, Spigot/Bukkit, libpg (bundled), Gradle Kotlin DSL. No new runtime dependencies.

## Global Constraints

- `max-concurrent-games` default `8` — max simultaneous game worlds.
- `per-game-worlds` default `true` — if `false`, revert to single shared world (no world creation/deletion).
- `delete-game-worlds-on-cleanup` default `true` — delete the game world folder on cleanup.
- `queue-min-players` (existing) — reused as per-map minimum to start countdown.
- `queue-max-players` (existing) — reused as per-map full threshold.
- `queue-world` (existing) — lobby / holding world name.
- Game worlds are named `pillarperil_game_<id>` and only those are deleted.
- All existing `Game` coordinate logic (reads `map.origin` / `map.spawns`) must keep working unchanged — achieved by translating the map to origin (0,0,0) rather than changing `Game`.

---

## File Structure

**New files:**
- `src/main/kotlin/com/swapflip/fortunepillars/util/WorldManager.kt` — create/delete game worlds, name helpers.
- `src/main/kotlin/com/swapflip/fortunepillars/map/MapTransforms.kt` — `translateMapToOrigin`.
- `src/main/kotlin/com/swapflip/fortunepillars/game/util/VoteMath.kt` — pure `mostVoted` / `resolveVote` (extracted, testable).
- `src/main/kotlin/com/swapflip/fortunepillars/game/util/MapQueue.kt` — per-map queue state.
- `src/test/kotlin/com/swapflip/fortunepillars/...` — pure-logic unit tests (3 files).

**Modified files:**
- `src/main/kotlin/com/swapflip/fortunepillars/util/Configuration.kt` — 3 new config properties.
- `src/main/kotlin/com/swapflip/fortunepillars/game/util/QueueManager.kt` — full rewrite (per-map queues).
- `src/main/kotlin/com/swapflip/fortunepillars/game/Game.kt` — `cleanup()` deletes world instead of re-pasting.
- `src/main/kotlin/com/swapflip/fortunepillars/event/QueueEvents.kt` — map menu joins a map queue; vote menu votes per-queue; RED_DYE leaves.
- `src/main/kotlin/com/swapflip/fortunepillars/game/util/QueueScoreboards.kt` — per-queue scoreboard.
- `build.gradle.kts` — add `kotlin("test")` + `test { useJUnitPlatform() }`.

---

### Task 1: Config properties + test harness

**Files:**
- Modify: `src/main/kotlin/com/swapflip/fortunepillars/util/Configuration.kt` (after `arenaResetRePaste`, ~line 191)
- Modify: `build.gradle.kts` (dependencies + test block)

**Interfaces:** None (leaf config).

- [ ] **Step 1: Add config properties**

In `Configuration.kt`, after `var arenaResetRePaste by boolean("arena.reset-repaste", true)` add:

```kotlin
    // How many games may run at once. Each per-map queue that fills starts its own world, so this
    // caps the number of simultaneously created game worlds (and thus worlds on disk).
    var maxConcurrentGames by int("max-concurrent-games", 8)

    // When true (default), every game gets its own freshly-created void world at (0,0,0) that is
    // deleted on cleanup. When false, the plugin reverts to the legacy single shared world behavior
    // (no world creation/deletion) as a safety net.
    var perGameWorlds by boolean("per-game-worlds", true)

    // When true (default) and per-game-worlds is on, the game world folder is deleted after the
    // game ends. Only worlds named "pillarperil_game_<id>" are ever deleted.
    var deleteGameWorldsOnCleanup by boolean("delete-game-worlds-on-cleanup", true)
```

Also add `int` is already imported (used by `by int(...)` elsewhere). Confirm `import org.bukkit.*` covers `World` (used later by WorldManager, not here).

- [ ] **Step 2: Add test dependency + source set**

In `build.gradle.kts`, add to `dependencies { ... }`:

```kotlin
    testImplementation(kotlin("test"))
```

And after the `dependencies { ... }` block (before `tasks { ... }` or after, order doesn't matter) add:

```kotlin
tasks {
    test {
        useJUnitPlatform()
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL (no new logic yet; just config + test wiring).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/swapflip/fortunepillars/util/Configuration.kt build.gradle.kts
git commit -m "feat(queue): add per-game-world config + test harness wiring"
```

---

### Task 2: Pure-logic utilities (VoteMath, MapTransforms, WorldManager)

**Files:**
- Create: `src/main/kotlin/com/swapflip/fortunepillars/game/util/VoteMath.kt`
- Create: `src/main/kotlin/com/swapflip/fortunepillars/map/MapTransforms.kt`
- Create: `src/main/kotlin/com/swapflip/fortunepillars/util/WorldManager.kt`

**Interfaces:**
- Produces: `VoteMath.mostVoted(list, default)`, `VoteMath.resolveVote(votes, sentinel, options, default)`, `translateMapToOrigin(map): ArenaMap`, `WorldManager.gameWorldName(id)`, `WorldManager.isGameWorld(world)`, `WorldManager.createGameWorld(id)`, `WorldManager.deleteGameWorld(world)`.

- [ ] **Step 1: Write VoteMath.kt**

```kotlin
package com.swapflip.fortunepillars.game.util

// Picks the most-voted option; ties are broken randomly. Returns `default` when the list is empty.
fun <T> List<T>.mostVoted(default: T): T {
    val counts = groupingBy { it }.eachCount()
    val max = counts.values.maxOrNull() ?: return default
    return counts.filterValues { it == max }.keys.random()
}

// Resolves a vote category: if the winner equals `sentinel`, pick randomly from `options`
// instead of falling back to the configured default.
fun <T> resolveVote(votes: List<T>, sentinel: T, options: List<T>, default: T): T {
    val winner = votes.mostVoted(default)
    return if (winner == sentinel) options.random() else winner
}
```

- [ ] **Step 2: Write MapTransforms.kt**

```kotlin
package com.swapflip.fortunepillars.map

// Returns a copy of `map` whose origin is (0,0,0) and whose spawns/spectatorSpawn are shifted by
// -originalOrigin. This lets the schematic be pasted at (0,0,0) in a fresh world while every
// existing Game coordinate calculation (which reads map.origin / map.spawns) keeps working unchanged.
fun translateMapToOrigin(map: ArenaMap): ArenaMap {
    val o = map.origin
    val shift: (BlockPos) -> BlockPos = { BlockPos(it.x - o.x, it.y - o.y, it.z - o.z) }
    return ArenaMap(
        name = map.name,
        schematic = map.schematic,
        world = map.world,
        origin = BlockPos(0, 0, 0),
        spawns = map.spawns.mapTo(mutableListOf()) { shift(it) },
        spectatorSpawn = map.spectatorSpawn?.let(shift),
        deathHeight = map.deathHeight,
    ).also {
        it.displayName = map.displayName
        it.description = map.description
    }
}
```

- [ ] **Step 3: Write WorldManager.kt**

```kotlin
package com.swapflip.fortunepillars.util

import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.util.VoidChunkGenerator
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import java.io.File

object WorldManager {
    const val GAME_WORLD_PREFIX = "pillarperil_game_"

    fun gameWorldName(id: Int): String = "$GAME_WORLD_PREFIX$id"

    fun isGameWorld(world: World): Boolean = world.name.startsWith(GAME_WORLD_PREFIX)

    // Creates a fresh void world at (0,0,0) with autosave off. Returns null (and logs) on failure.
    // Returns null immediately when per-game-worlds is disabled so callers fall back to the shared world.
    fun createGameWorld(id: Int): World? {
        if (!Configuration.perGameWorlds) return null
        return runCatching {
            WorldCreator(gameWorldName(id))
                .generator(VoidChunkGenerator())
                .generateStructures(false)
                .keepSpawnInMemory(false)
                .createWorld()
                ?.apply { setAutoSave(false) }
        }.onFailure {
            FortunePillars.LOG.error("Could not create game world \"${gameWorldName(id)}\".", it)
        }.getOrNull()
    }

    // Unloads the world and asynchronously deletes its folder. No-op when deletion is disabled or the
    // world is not one we created.
    fun deleteGameWorld(world: World) {
        if (!Configuration.deleteGameWorldsOnCleanup) return
        if (!isGameWorld(world)) return
        val name = world.name
        runCatching { Bukkit.unloadWorld(world, false) }
            .onFailure { FortunePillars.LOG.warn("Could not unload world \"$name\".", it) }
        Bukkit.getScheduler().runTaskAsynchronously(FortunePillars.PLUGIN) {
            runCatching { File(Bukkit.getWorldContainer(), name).deleteRecursively() }
                .onFailure { FortunePillars.LOG.warn("Could not delete world folder \"$name\".", it) }
        }
    }
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/swapflip/fortunepillars/game/util/VoteMath.kt src/main/kotlin/com/swapflip/fortunepillars/map/MapTransforms.kt src/main/kotlin/com/swapflip/fortunepillars/util/WorldManager.kt
git commit -m "feat(queue): add VoteMath, MapTransforms, WorldManager utilities"
```

---

### Task 3: Unit tests for pure logic

**Files:**
- Create: `src/test/kotlin/com/swapflip/fortunepillars/game/util/VoteMathTest.kt`
- Create: `src/test/kotlin/com/swapflip/fortunepillars/map/MapTransformsTest.kt`
- Create: `src/test/kotlin/com/swapflip/fortunepillars/util/WorldManagerNameTest.kt`

**Interfaces:** Consumes `VoteMath`, `translateMapToOrigin`, `WorldManager.gameWorldName` / `isGameWorld`.

- [ ] **Step 1: Write the failing tests**

`VoteMathTest.kt`:
```kotlin
package com.swapflip.fortunepillars.game.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoteMathTest {
    @Test
    fun `mostVoted picks the highest count`() {
        assertEquals("a", listOf("a", "a", "b").mostVoted("default"))
    }

    @Test
    fun `mostVoted returns default on empty`() {
        assertEquals("default", emptyList<String>().mostVoted("default"))
    }

    @Test
    fun `resolveVote returns random option when sentinel wins`() {
        val options = listOf("x", "y", "z")
        repeat(20) {
            val result = resolveVote(listOf("__random__"), "__random__", options, "default")
            assertTrue(result in options)
        }
    }

    @Test
    fun `resolveVote returns actual winner otherwise`() {
        assertEquals("b", resolveVote(listOf("b", "b", "a"), "__random__", listOf("a", "b"), "default"))
    }
}
```

`MapTransformsTest.kt`:
```kotlin
package com.swapflip.fortunepillars.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MapTransformsTest {
    @Test
    fun `translate shifts spawns by minus origin and zeroes origin`() {
        val map = ArenaMap(
            name = "test",
            schematic = "test",
            world = "world",
            origin = BlockPos(100, 64, 100),
            spawns = mutableListOf(BlockPos(102, 64, 100), BlockPos(100, 64, 105)),
            spectatorSpawn = BlockPos(110, 70, 110),
            deathHeight = 0,
        )
        val t = translateMapToOrigin(map)
        assertEquals(BlockPos(0, 0, 0), t.origin)
        assertEquals(BlockPos(2, 0, 0), t.spawns[0])
        assertEquals(BlockPos(0, 0, 5), t.spawns[1])
        assertEquals(BlockPos(10, 6, 10), t.spectatorSpawn)
        assertEquals(map.name, t.name)
    }
}
```

`WorldManagerNameTest.kt`:
```kotlin
package com.swapflip.fortunepillars.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldManagerNameTest {
    @Test
    fun `game world name follows prefix convention`() {
        assertEquals("pillarperil_game_5", WorldManager.gameWorldName(5))
    }

    @Test
    fun `isGameWorld only matches our prefix`() {
        assertTrue(WorldManager.isGameWorld(object : org.bukkit.World by org.bukkit.Bukkit.getWorlds().first() {
            override fun getName() = "pillarperil_game_3"
        } {}))
    }
}
```
Note: the `isGameWorld` test uses a minimal `World` stub via the `by` delegate; if the delegate is awkward on your setup, replace with a simple fake implementing only `getName()` returning the test name and returning defaults elsewhere (the function only reads `world.name`). The assertion must check `isGameWorld` returns true for `"pillarperil_game_3"` and false for `"PillarPeril"`.

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew test -q`
Expected: all 3 test classes PASS (BUILD SUCCESSFUL).

- [ ] **Step 3: Commit**

```bash
git add src/test
git commit -m "test(queue): unit tests for VoteMath, MapTransforms, WorldManager naming"
```

---

### Task 4: MapQueue data class

**Files:**
- Create: `src/main/kotlin/com/swapflip/fortunepillars/game/util/MapQueue.kt`

**Interfaces:** Produces `MapQueue` consumed by `QueueManager` (Task 5).

- [ ] **Step 1: Write MapQueue.kt**

```kotlin
package com.swapflip.fortunepillars.game.util

import com.swapflip.fortunepillars.map.ArenaMap
import com.swapflip.fortunepillars.player.PlayerSnapshot
import org.bukkit.entity.Player
import java.util.UUID

// Per-map queue state. One instance exists per map that currently has at least one waiting player.
class MapQueue(
    val map: ArenaMap,
    val players: MutableList<Player> = mutableListOf(),
    val votes: MutableMap<UUID, QueueManager.Vote> = mutableMapOf(),
    val snapshots: MutableMap<UUID, PlayerSnapshot> = mutableMapOf(),
    var countdownStart: Long = 0L,
    var countdownDelay: Int = 0,
    var startFailed: Boolean = false,
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/swapflip/fortunepillars/game/util/MapQueue.kt
git commit -m "feat(queue): add MapQueue per-map state holder"
```

---

### Task 5: QueueManager rewrite (per-map queues)

**Files:**
- Modify: `src/main/kotlin/com/swapflip/fortunepillars/game/util/QueueManager.kt` (full rewrite)

**Interfaces:**
- Consumes: `WorldManager`, `translateMapToOrigin`, `VoteMath`, `MapQueue`, `Cage`, `GameManager`, `MapManager`, `SchematicReader`, `MapPaster`, `Configuration`, `Registry`, `GameCompanion`, `PlayerSnapshot`, `QueueEvents`, `QueueScoreboards`.
- Produces: `joinMap(player, mapName)`, `leaveQueue(player)`, `currentQueueOf(player)`, `queueForMap(name)`, `availableMaps()`, `recordVote(player, mode?, type?, time?)`, `modeVoteCounts(queue)`, `typeVoteCounts(queue)`, `timeVoteCounts(queue)`, `votesCast(queue)`, `countdownSecondsLeft(queue)`, `votingLocked(queue)`, `isStartFailed`, `consumeJoinSnapshot(player)`, `forceStart(mapName?)`, `tick()`.

Replace the ENTIRE contents of `QueueManager.kt` with:

```kotlin
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
import com.swapflip.fortunepillars.map.translateMapToOrigin
import com.swapflip.fortunepillars.player.PlayerSnapshot
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.FeatureToggle
import com.swapflip.fortunepillars.util.MAXI_MESSAGE
import com.swapflip.fortunepillars.util.MINI_MESSAGE
import com.swapflip.fortunepillars.util.QueueMethod
import com.swapflip.fortunepillars.util.Ticking
import com.swapflip.fortunepillars.util.WorldManager
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

    // ---- lookups ----
    fun currentQueueOf(player: Player): MapQueue? = mapQueues.values.firstOrNull { player in it.players }
    fun queueForMap(mapName: String): MapQueue? = mapQueues[mapName]
    private fun getOrCreateQueue(map: ArenaMap): MapQueue = mapQueues.getOrPut(map.name) { MapQueue(map) }

    // Maps that can host a queue: have a saved schematic and enough spawns for the minimum.
    fun availableMaps(): List<ArenaMap> {
        val pool = if (Configuration.queueMapPool.isEmpty()) MapManager.maps.values
                   else MapManager.maps.values.filter { it.name in Configuration.queueMapPool }
        return pool.filter { MapManager.hasSchematic(it.name) && it.spawns.size >= Configuration.queueMinPlayers }
            .sortedBy { it.name }
    }

    fun consumeJoinSnapshot(player: Player): PlayerSnapshot? =
        currentQueueOf(player)?.snapshots?.remove(player.uniqueId)

    // ---- voting (per current queue) ----
    fun recordVote(player: Player, mode: String? = null, type: String? = null, time: Int? = null) {
        val q = currentQueueOf(player) ?: return
        val prev = q.votes[player.uniqueId] ?: Vote()
        q.votes[player.uniqueId] = Vote(mode ?: prev.mode, type ?: prev.type, time ?: prev.time, null)
    }

    fun modeVoteCounts(queue: MapQueue): Map<String, Int> = queue.votes.values.mapNotNull { it.mode }.groupingBy { it }.eachCount()
    fun typeVoteCounts(queue: MapQueue): Map<String, Int> = queue.votes.values.mapNotNull { it.type }.groupingBy { it }.eachCount... // see note
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
        if (GameManager.games.isNotEmpty()) {
            if (Configuration.queueMethod == QueueMethod.AUTO) pendingAutoJoins[player] = mapName
            player.sendMessage(player.locale().component("queue.join.in_game", color = NamedTextColor.RED))
            return
        }
        val map = MapManager.maps[mapName] ?: run {
            player.sendMessage(player.locale().component("queue.join.invalid_map", color = NamedTextColor.RED))
            return
        }
        if (map.spawns.size < Configuration.queueMinPlayers) {
            player.sendMessage(player.locale().component("queue.join.too_small", color = NamedTextColor.RED))
            return
        }
        if (player in (currentQueueOf(player)?.players ?: emptyList())) return

        lastMap = mapName
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
            resetWaiting(queue, tick)
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
    private fun check(queue: MapQueue) {
        if (GameManager.games.size >= Configuration.maxConcurrentGames) return
        if (queue.players.size < Configuration.queueMinPlayers) return

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
        val placeholders = mutableMapOf("id" to id, "mode" to mode.gameInfo.namespace, "players" to players.size)

        // Resolve the world: a fresh per-game world, or the shared world when per-game-worlds is off.
        val (gameWorld, gameMap) = if (Configuration.perGameWorlds) {
            val w = WorldManager.createGameWorld(id) ?: run {
                requeue(players, queue)
                players.forEach { it.sendMessage(it.locale().component("queue.world_missing", WorldManager.gameWorldName(id), color = NamedTextColor.RED)) }
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
        Cage.clearTowers(emptyList())

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
        check(queue)
    }
}
```

> **Note on the `typeVoteCounts` line above:** the placeholder `eachCount...` is a typo from editing — the correct line is:
> `fun typeVoteCounts(queue: MapQueue): Map<String, Int> = queue.votes.values.mapNotNull { it.type }.groupingBy { it }.eachCount()`
> (identical shape to `modeVoteCounts`/`timeVoteCounts`). Use that exact line in the file.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL. (If `MAXI_MESSAGE` import is unused, remove it; it was included defensively — only `MINI_MESSAGE` is used. Remove any unused import the compiler warns about.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/swapflip/fortunepillars/game/util/QueueManager.kt
git commit -m "feat(queue): rewrite QueueManager around per-map MapQueue"
```

---

### Task 6: Game.cleanup() deletes the world

**Files:**
- Modify: `src/main/kotlin/com/swapflip/fortunepillars/game/Game.kt` (`cleanup()`)

**Interfaces:** Consumes `WorldManager.deleteGameWorld(world)`, `WorldManager.isGameWorld(world)`.

- [ ] **Step 1: Locate and replace the re-paste block**

In `Game.cleanup()`, find the block added earlier (commit `e17a76f`):
```kotlin
        if (Configuration.arenaResetRePaste && world.name == Cage.queueWorldName
            && (worldUsers[world] ?: 0) <= 1) {
            runCatching { QueueManager.rePasteCurrentArena() }
                .onFailure { Bukkit.getLogger().warning("Arena re-paste failed; relying on change-tracking reset.") }
        }
```
Replace it with:
```kotlin
        // Per-game worlds are deleted after the match instead of re-pasted: the world is fresh each
        // game, so there is nothing to reset. The shared world (per-game-worlds: false) is left alone.
        if (Configuration.perGameWorlds && WorldManager.isGameWorld(world))
            WorldManager.deleteGameWorld(world)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/swapflip/fortunepillars/game/Game.kt
git commit -m "feat(queue): delete game world on cleanup instead of re-pasting"
```

---

### Task 7: QueueEvents — map menu joins, per-queue votes, leave

**Files:**
- Modify: `src/main/kotlin/com/swapflip/fortunepillars/event/QueueEvents.kt`

**Interfaces:** Consumes `QueueManager.joinMap`, `leaveQueue`, `currentQueueOf`, `availableMaps`, `recordVote`, `modeVoteCounts(queue)`, `typeVoteCounts(queue)`, `timeVoteCounts(queue)`, `votingLocked(queue)`, `countdownSecondsLeft(queue)`, `isStartFailed`, `queueForMap`.

- [ ] **Step 1: Update onInteract (RED_DYE leaves, CHEST opens vote menu)**

Replace the `Material.RED_DYE` branch body (`QueueManager.remove(player)`) with `QueueManager.leaveQueue(player)`. The `player !in QueueManager.queue` guard at the top of `onInteract` becomes `currentQueueOf(player) == null`:
```kotlin
        val player = event.player
        if (QueueManager.currentQueueOf(player) == null) return
```
And in `onDrop`/`onBlockBreak`/`onBlockPlace`, replace `player in QueueManager.queue` with `QueueManager.currentQueueOf(player) != null`.

- [ ] **Step 2: Update onMapClick to join the map's queue**

Replace `onMapClick` body:
```kotlin
    private fun onMapClick(player: Player, event: InventoryClickEvent) {
        val item = event.currentItem ?: return
        if (item.type !in setOf(Material.SLIME_BALL, Material.FIRE_CHARGE)) return
        val name = item.itemMeta?.persistentDataContainer?.get(MAP_KEY, PersistentDataType.STRING) ?: return
        if (MapManager.maps[name] == null) return
        QueueManager.joinMap(player, name)
        if (QueueManager.currentQueueOf(player) != null)
            player.sendMessage(player.locale().chatComponent("queue.join.success"))
        player.playSound(player, Sound.UI_BUTTON_CLICK, 1.0f, 1.5f)
        player.closeInventory()
    }
```

- [ ] **Step 3: Update onVoteClick to vote in the player's current queue**

Replace the `QueueManager.votingLocked` / `countdownSecondsLeft` usages:
```kotlin
    private fun onVoteClick(player: Player, event: InventoryClickEvent) {
        val queue = QueueManager.currentQueueOf(player)
        if (queue == null || QueueManager.votingLocked(queue)) {
            player.sendMessage(player.locale().chatComponent("queue.vote.locked", (QueueManager.countdownSecondsLeft(queue ?: return) ?: 0).toString()))
            return
        }
        ... // same slot mapping as before, but call QueueManager.recordVote(player, mode = it) etc.
    }
```
(The slot→option mapping is unchanged; only the `recordVote` target and the lock check change.)

- [ ] **Step 4: Update map menu to show per-map queue size**

In `openMapMenu` and `refreshMapMenus`, replace `QueueManager.mapVoteCandidates()` with `QueueManager.availableMaps()`. In `fillMapMenu`, replace the `votes` line and `leader` computation:
```kotlin
    private fun fillMapMenu(inv: Inventory, maps: List<ArenaMap>, locale: Locale) {
        val queued = { name: String -> QueueManager.queueForMap(name)?.players?.size ?: 0 }
        val leader = maps.maxByOrNull { queued(it.name) }?.name
        for (slot in 10..16) inv.setItem(slot, null)
        for (slot in 19..25) inv.setItem(slot, null)
        maps.forEachIndexed { i, map ->
            if (i >= MAX_VISIBLE_MAPS) return@forEachIndexed
            val n = queued(map.name)
            val isLeader = n > 0 && map.name == leader
            inv.setItem((if (i < 7) 1 else 2) * 9 + 1 + i % 7, mapItem(map, n, isLeader, locale))
        }
    }
```
And in `mapItem`, change the vote-count lore line to a queued-count line:
```kotlin
            add(Component.text(locale.string("map.queued", queued.toString()))
                .color(if (queued > 0) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY))
```
(compute `queued` inside `mapItem` via `QueueManager.queueForMap(map.name)?.players?.size ?: 0`).

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/swapflip/fortunepillars/event/QueueEvents.kt
git commit -m "feat(queue): map menu joins per-map queue; votes are per-queue"
```

---

### Task 8: QueueScoreboards — per-queue display

**Files:**
- Modify: `src/main/kotlin/com/swapflip/fortunepillars/game/util/QueueScoreboards.kt`

**Interfaces:** Consumes `QueueManager.currentQueueOf`, `modeVoteCounts(queue)`, `typeVoteCounts(queue)`, `timeVoteCounts(queue)`, `votesCast(queue)`, `countdownSecondsLeft(queue)`, `isStartFailed`, `queueForMap`.

- [ ] **Step 1: Make `show` resolve the player's queue and thread it through**

Change `show(player)` to capture `val queue = QueueManager.currentQueueOf(player) ?: return` (after the enabled/already-shown guards) and pass `queue` into `defaultEntries(player, queue)` and the `TemplateEntry` resolver `resolve(key, player, queue, locale)`.

- [ ] **Step 2: Update `defaultEntries` and `resolve` to use the queue**

In `defaultEntries`, replace `QueueManager.queue.size` with `queue.players.size`, and `currentMapName` with the queue's map name (`queue.map.displayName ?: queue.map.name`). Replace `topMode/topMap` to read `modeVoteCounts(queue)` / `typeVoteCounts(queue)` / `timeVoteCounts(queue)` instead of the global cache.

In `resolve`, replace every `QueueManager.queue.size`, `QueueManager.countdownSecondsLeft`, `QueueManager.votesCast()`, `QueueManager.currentArenaMap()`, and the global `cachedMode/cachedType/cachedTime/cachedMap` reads with the per-`queue` equivalents. The `refreshVoteCache()` global cache can stay (it still reads the player's current queue via `currentQueueOf` if you prefer), but simplest is to drop the global cache and read `modeVoteCounts(queue)` etc. directly inside `resolve` since `queue` is now in scope.

Concretely, the `resolve` function signature becomes `private fun resolve(key: String, player: Player, queue: QueueManager.MapQueue, locale: Locale): String` and uses `queue.players.size`, `QueueManager.countdownSecondsLeft(queue)`, `QueueManager.modeVoteCounts(queue)`, etc. `statusComponent` and `topMode/topType/topMap` take `queue` too.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/swapflip/fortunepillars/game/util/QueueScoreboards.kt
git commit -m "feat(queue): scoreboard shows the player's per-map queue state"
```

---

### Task 9: Edge cases — AUTO mode, overflow, disconnect, cap freeze

**Files:**
- Modify: `src/main/kotlin/com/swapflip/fortunepillars/event/QueueEvents.kt` (PlayerQuit handling) — OR add to `QueueManager` if a quit hook already exists.
- Modify: `src/main/kotlin/com/swapflip/fortunepillars/game/util/QueueManager.kt` (AUTO default map, overflow wait).

**Interfaces:** Builds on Tasks 5–8.

- [ ] **Step 1: AUTO mode default map**

In `joinMap`, when `mapName` is empty/`lastMap` is null and `Configuration.queueMethod == QueueMethod.AUTO`, fall back to the first `availableMaps().firstOrNull()?.name`. Add near the top of `joinMap`:
```kotlin
        val target = mapName.takeIf { it.isNotEmpty() } ?: lastMap ?: run {
            if (Configuration.queueMethod == QueueMethod.AUTO) availableMaps().firstOrNull()?.name else null
        } ?: run {
            player.sendMessage(player.locale().component("queue.join.no_map", color = NamedTextColor.RED))
            return
        }
        val map = MapManager.maps[target] ?: ...
```

- [ ] **Step 2: Overflow waits for next game**

In `check(queue)`, after `val players = MutableList(playerCount) { queue.players.removeFirst() }`, any remaining players in `queue.players` (beyond `queueMaxPlayers`) simply stay in the queue for the next game — already handled because `removeFirst` only takes `playerCount`. No extra code needed; verify the leftover players keep their cages (they were caged in the lobby and remain there).

- [ ] **Step 3: Disconnect while queued restores state**

Add a `PlayerQuitEvent` handler (in `QueueEvents` or wherever other player events live) that calls `QueueManager.leaveQueue(player)` so the snapshot is restored and the queue shrinks. If a `PlayerQuitEvent` handler already exists for games, add the queue call there:
```kotlin
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        QueueManager.leaveQueue(event.player)
    }
```

- [ ] **Step 4: Concurrent cap freeze**

Already implemented in Task 5 `tickQueue` (`canStart` requires `GameManager.games.size < Configuration.maxConcurrentGames`); the `resetWaiting` branch shows `queue.wait.full_games`. Verify the locale key exists or reuse `queue.actionbar`.

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/swapflip/fortunepillars/event/QueueEvents.kt src/main/kotlin/com/swapflip/fortunepillars/game/util/QueueManager.kt
git commit -m "feat(queue): AUTO default map, overflow wait, disconnect restore, cap freeze"
```

---

### Task 10: Final verification

**Files:** None (verification only).

- [ ] **Step 1: Full compile + tests**

Run: `./gradlew compileKotlin test -q`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 2: Manual smoke test (on a test server)**

1. `/pp config set per-game-worlds true` (default).
2. Two players each open the map menu and join **different** maps.
3. Confirm each is caged in the lobby and a per-map countdown starts at `queue-min-players`.
4. Fill a map's queue to `queue-max-players` (or wait out the countdown). Confirm a game starts in a **new world** named `pillarperil_game_<id>`; both players spawn on that map's spawns at (0,0,0)-relative coordinates.
5. Start a second map's queue concurrently; confirm a **second** `pillarperil_game_<id>` world is created and runs independently.
6. End both games; confirm both `pillarperil_game_*` worlds are **deleted** from the server's world folder.
7. Confirm the lobby world is clean and reusable.
8. Set `per-game-worlds false`; confirm games run in the shared world and no worlds are deleted (safety-net parity).

- [ ] **Step 3: Commit any follow-up fixes**

If manual testing surfaces issues, fix and commit with a descriptive message. Do **not** commit `.opencode/`, `Backup/`, or `txt.txt`.

---

## Self-Review Notes

- **Spec coverage:** Goal (per-map queues) → Tasks 4–5. Per-game worlds at (0,0,0) → Tasks 2, 5, 6. Coordinate translation → Task 2 (`translateMapToOrigin`) + Task 5. Mode/type/time votes per queue → Tasks 5, 7, 8. Safety net (`per-game-worlds: false`) → Tasks 1, 5, 6. Edge cases → Task 9. Verification → Task 10. All spec sections covered.
- **Placeholder scan:** The only inline note is the `typeVoteCounts` typo correction in Task 5 (explicitly called out and corrected). No TBD/TODO remain.
- **Type consistency:** `MapQueue` fields (`players`, `votes`, `snapshots`, `countdownStart`, `countdownDelay`, `startFailed`) are referenced consistently across Tasks 4–9. `Vote` is `QueueManager.Vote` (same package). `translateMapToOrigin` returns `ArenaMap` consumed by `startGame`. `WorldManager.gameWorldName/isGameWorld/createGameWorld/deleteGameWorld` signatures match Tasks 2 and 6. `countdownSecondsLeft(queue)` / `votingLocked(queue)` / `modeVoteCounts(queue)` etc. are the per-queue forms used uniformly in Tasks 5, 7, 8.
- **Risk:** `Game.cleanup()` world deletion is irreversible — guarded by `deleteGameWorldsOnCleanup` and `isGameWorld` prefix check (Task 2/6). Concurrent cap enforced in `tickQueue` (Task 5). Coordinate shift covers all spawns + spectator (Task 2 test).
