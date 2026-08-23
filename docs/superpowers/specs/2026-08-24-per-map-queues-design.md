# Per-Map Queue System (Hypixel/BedWars-style)

**Date:** 2026-08-24
**Status:** Design approved (user: "yea go")
**Branch:** `fix/queue-world-precreation`
**Plugin:** PillarPeril (`com.swapflip.fortunepillars`, Kotlin/Spigot-Paper)

---

## 1. Goal

Replace the current **single global vote-queue** (one queue, one map vote resolved at
start, all games in one admin-built world) with a **per-map queue system** like Hypixel /
BedWars:

- A player **joins a specific map's queue** (not a global pool).
- When a map's queue reaches its minimum player threshold, a **countdown** starts.
- When the queue is **full enough** (or countdown elapses), a game **starts for that map**.
- Multiple maps can run **concurrently**, each in its **own freshly-created world at (0,0,0)**.
- The game world is **deleted after the game ends**.

The map *vote* becomes redundant (the map is fixed by which queue you joined); mode / type /
time remain **per-queue votes**.

---

## 2. Decisions (from clarifying Q&A)

| # | Question | Decision |
|---|----------|----------|
| Q1 | How to handle multiple maps at once? | **Per-game worlds at (0,0)** — each game gets its own void world; multiple maps run concurrently in different worlds. |
| Q2 | What about mode / type / time selection? | **Keep as votes, per map queue** — map is fixed by queue; mode/type/time still voted within the queue. |
| Q3 | World lifecycle? | **Fresh world per game + delete folder on cleanup.** |

**Safety net:** a `per-game-worlds` config flag (default `true`) reverts to the current
single-world sequential behavior if set to `false`.

---

## 3. Architecture

```
                 ┌─────────────────────────────────────────────┐
                 │              QueueManager                    │
                 │  mapQueues: MutableMap<String, MapQueue>     │
                 │    "map_a" -> MapQueue(players, votes, ...)  │
                 │    "map_b" -> MapQueue(...)                  │
                 └───────────────┬─────────────────────────────┘
                                 │ joinMap / leaveQueue / tick
                                 ▼
        ┌────────────────────────────────────────────────────────┐
        │  MapQueue (per-map state)                                │
        │   - map: ArenaMap (original, admin-built)                │
        │   - players: MutableList<Player> (ordered)              │
        │   - votes: mode / type / time tallies                   │
        │   - countdownActive, countdownSecondsLeft               │
        │   - snapshots: Map<UUID, PlayerSnapshot>                 │
        │   - startFailed: Boolean                                │
        └────────────────────────────────────────────────────────┘
                                 │ when full && games.size < max
                                 ▼
        ┌────────────────────────────────────────────────────────┐
        │  WorldManager (new)                                      │
        │   createGameWorld(id) -> World (void, 0,0,0, no autosave)│
        │   deleteGameWorld(world) -> unload + async folder delete │
        └────────────────────────────────────────────────────────┘
                                 │
                                 ▼
        ┌────────────────────────────────────────────────────────┐
        │  Game.startGame(players, mode, type, time, map, queue)   │
        │   1. create world                                        │
        │   2. translate map -> origin (0,0,0) + paste schematic   │
        │   3. teleport players from lobby into world spawns       │
        │   4. construct Game(world=gameWorld, map=gameMap, ...)   │
        └────────────────────────────────────────────────────────┘
                                 │ on end
                                 ▼
        Game.cleanup() -> deleteGameWorld(world)  (no re-paste; world gone)
```

### 3.1 Holding / lobby world

The **queue/holding world** remains a single void-generated world (`Cage.lobby`, currently
`Cage.ensureQueueWorld`). Players are caged there while waiting in a map queue. This is
**not** a game world — it is reused across all queues.

### 3.2 Coordinate translation

`ArenaMap` stores `origin` (paste anchor in the admin-built world) and `spawns` /
`spectatorSpawn` as **absolute** `BlockPos` in that origin's space. `Game` reads
`map.origin` and `map.spawns` directly, so we must keep those semantics valid in the new
world:

- Clone the `ArenaMap` with `origin = BlockPos(0, 0, 0)`.
- Shift every `spawn` and `spectatorSpawn` by `-originalOrigin` (so they become relative to
  `(0,0,0)`).
- Paste the schematic at `(0, 0, 0)` in the fresh void world.

Result: all existing `Game` coordinate logic (center = `map.origin`, arena anchor math,
`cagePlayersOnMap`, `startOnMap`) works **unchanged** because the map now genuinely lives at
the origin of its own world.

---

## 4. Components & Changes

### 4.1 New: `MapQueue` (data class / class in `game/util/QueueManager.kt` or own file)

Encapsulates per-map queue state:

```kotlin
class MapQueue(
    val map: ArenaMap,
    val players: MutableList<Player> = mutableListOf(),
    val modeVotes: MutableMap<String, Int> = mutableMapOf(),
    val typeVotes: MutableMap<String, Int> = mutableMapOf(),
    val timeVotes: MutableMap<Int, Int> = mutableMapOf(),
    var countdownActive: Boolean = false,
    var countdownSecondsLeft: Int = 0,
    val snapshots: MutableMap<UUID, PlayerSnapshot> = mutableMapOf(),
    var startFailed: Boolean = false,
)
```

### 4.2 `QueueManager` refactor

- **Remove:** single `queue: ArrayDeque<Player>`, single `votes`/`countdown`/`add` flow,
  `arenaMap`, queue-world arena preview (`pasteMap`, `growArenaIfNeeded`, `applyMapVote`,
  map-vote handling, `rePasteCurrentArena`).
- **Add:** `mapQueues: MutableMap<String, MapQueue>`.
- **`joinMap(player, mapName)`**: get-or-create the `MapQueue` for that map; snapshot the
  player; cage them in the lobby world; if `players.size >= queueMinPlayers`, start the
  countdown.
- **`leaveQueue(player)`**: remove the player from whichever `MapQueue` they are in; restore
  their snapshot; uncage.
- **`tick()`**: iterate all `mapQueues`; for each, decrement countdown; if
  `players.size >= queueMaxPlayers` (or countdown hits 0) **and** `GameManager.games.size <
  maxConcurrentGames`, call `check(queue)`.
- **`check(queue)`**: resolve mode/type/time votes (majority, fallback to config default);
  call `Game.startGame(queue.players, mode, type, time, queue.map, queue)`; on failure,
  requeue players (mirror current `requeue` path) and mark `startFailed`.
- **`currentQueueOf(player)`**: returns the `MapQueue` the player is in (for UI / scoreboard /
  vote commands).
- **`countdownSecondsLeft` / tallies accessors** for scoreboard + UI.

### 4.3 New: `WorldManager` (in `game/util/` or `util/`)

```kotlin
fun createGameWorld(id: Int): World? =
    runCatching {
        WorldCreator("pillarperil_game_$id")
            .generator(VoidChunkGenerator())
            .generateStructures(false)
            .keepSpawnInMemory(false)
            .createWorld()
            ?.apply { setAutoSave(false) }
    }.onFailure { warn(...) }.getOrNull()

fun deleteGameWorld(world: World) {
    Bukkit.unloadWorld(world, false)
    // async delete of the world folder; on failure, log + leave unloaded
    runTaskAsynchronously { world.worldFolder.deleteRecursively() }
}
```

`VoidChunkGenerator` already exists (`util/VoidChunkGenerator.kt`).

### 4.4 `Game` changes

- **`startGame(players, mode, type, time, map, queue)`** (signature extended): create the
  world via `WorldManager.createGameWorld(id)`; translate + paste the map at `(0,0,0)`;
  teleport players from the lobby into the new world's spawns; construct `Game` with
  `world = gameWorld`, `map = gameMap` (translated), `arenaBounds` from the paste result.
- **`cleanup()`**: remove the queue-world re-paste block added in the previous commit
  (`Configuration.arenaResetRePaste && world.name == Cage.queueWorldName ...`) — the world is
  deleted instead, so re-pasting is meaningless. Keep all other cleanup (snapshots, cages,
  entities, gamerules).
- **Admin `/game start`**: also routes through `createGameWorld` (dedicated world), not the
  shared `PillarPeril` world.

### 4.5 UI (`QueueEvents.kt`, `QueueScoreboards.kt`)

- **Map menu click** → `QueueManager.joinMap(player, mapName)` (was: record a map *vote* +
  add to global queue). Show `queued: N` / `playing: M` status.
- **Vote menu** → votes mode / type / time **within the player's current `MapQueue`** (was:
  global votes).
- **Scoreboard** → `QueueScoreboards.show(player, queue)` shows that queue's player count,
  mode/type/time tallies, and countdown.
- **RED_DYE** → `QueueManager.leaveQueue(player)` (leave current map queue).

### 4.6 Config additions (`util/Configuration.kt`)

| Key | Default | Meaning |
|-----|---------|---------|
| `max-concurrent-games` | `8` | Max simultaneous game worlds. |
| `per-game-worlds` | `true` | If `false`, revert to old single-world sequential behavior (safety net). |
| `delete-game-worlds-on-cleanup` | `true` | Delete the world folder on game end. |
| `queue-min-players` | (existing) | Reused as per-map min to start countdown. |
| `queue-max-players` | (existing) | Reused as per-map full threshold. |
| `queue-world` | (existing) | Lobby / holding world name. |

---

## 5. Data Flow

1. Player opens map menu, clicks a map → `joinMap(player, "map_a")`.
2. Player is snapshotted + caged in the **lobby** world; added to `mapQueues["map_a"]`.
3. When `map_a` queue hits `queue-min-players`, countdown starts.
4. When full (`queue-max-players`) or countdown ends **and** `games.size < max-concurrent-games`:
   - Resolve mode/type/time votes.
   - `WorldManager.createGameWorld(id)` → fresh void world at (0,0,0).
   - Translate `map_a` to origin (0,0,0), paste schematic.
   - Teleport queued players into the new world's spawns.
   - `Game` starts.
5. Game ends → `Game.cleanup()` → `WorldManager.deleteGameWorld(world)` (async folder delete).
6. Lobby world is left clean (players already teleported out / restored).

---

## 6. Edge Cases

- **World create / paste fails** → requeue players into their `MapQueue` (mirror current
  `requeue` path); mark `startFailed` so it retries rather than stranding.
- **`maxConcurrentGames` reached** → queue countdown **freezes** (does not start) until a slot
  frees; players stay caged in lobby.
- **AUTO mode** → join the last-selected map (`track lastMap`) or a configured default map.
- **Overflow past `queue-max-players`** → extra players wait for the **next** game of that map
  (queue retains them; only first `queue-max-players` start).
- **`per-game-worlds: false`** → fall back to old single-world (`PillarPeril`) sequential
  start; `WorldManager` not used.
- **Player disconnects while queued** → removed from `MapQueue`, snapshot restored.

---

## 7. Verification

- `./gradlew compileKotlin` passes.
- **Manual:** two different maps start concurrently in two separate worlds at (0,0,0); both
  worlds are deleted on game end; lobby world remains clean and reusable.
- **Safety net:** setting `per-game-worlds: false` reverts to prior behavior with no errors.
- **Regression:** existing mode/type/time vote resolution still works per queue; cage
  protection, scoreboard, and cosmetics unaffected.

---

## 8. Out of Scope (this design)

- Per-player language / i18n (explicitly excluded by user).
- Join **signs** / queue **BossBar** (available as a later follow-up).
- Full game-cycle state-machine rewrite (added `GamePhase` queryable enum instead).
- Slime/FAWE world adapters (the `WorldManager` abstraction leaves room for this later).

---

## 9. Risks

- **World folder deletion** is irreversible — guard with `delete-game-worlds-on-cleanup` and
  only delete worlds we created (`pillarperil_game_<id>` naming).
- **Concurrent game cap** must be enforced consistently in `tick()` to avoid exceeding
  `max-concurrent-games`.
- **Coordinate translation** must shift **all** spawn/spectator positions; a missed shift
  would place players outside the pasted arena. Covered by the clone-with-shift approach.
