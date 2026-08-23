# FortunePillars

Open-source "Fortune Pillars"-style minigame for Paper 1.20.x: players spawn on floating pillars
trapped in glass cages, the cages shatter, and everyone receives random loot every few seconds
while building, fighting and knocking each other off — the classic CubeCraft experience, recreated.

## Features

- **Queue module** — players join the queue, vote on the game mode / modifier / item time / map,
  and a game starts automatically once enough players are in. Both `/queue`-based (command) and
  fully automatic (auto) methods are supported.
- **4 game modes** — normal (classic Pillars of Fortune loot), blocky (blocks & occasional PvP
  gear), action (multi-drops and power-ups everywhere), and op (netherite, bosses, instant-win
  gear).
- **12 modifiers** — optional per-game twists: rising lava, TNT falls, arrow rain, lightning,
  moonwalk, chain swap, vertical lock, ablockalypse, lava floor, UHC, speedrunner and normal.
- **Curated loot system** — a built-in weighted pool of weapons, armor, tools, blocks, food,
  potions and combat spawn eggs (Cubecraft-style), no junk. Power-ups can replace drops with
  useful items or glowing special items (Super Star, Fireball, Aid Platform).
- **Map system** — play on hand-built arenas: save builds as schematics, define spawns, a
  spectator spawn and a per-map death height, and let the plugin restore the arena after every
  match.
- **Arena restoration** — every placed/removed/exploded block is tracked and restored after the
  match; dropped items, arrows, TNT, mobs and falling blocks are cleaned up automatically.
- **Multi-language** — all messages are locale keys; ship your own `.properties` files.
- **PlaceholderAPI** — `%fp_playing%` shows how many players are currently in games.

## Requirements

- Paper 1.20.4+ (1.20 API)
- Java 17+
- Optional: PlaceholderAPI (soft dependency)

## Installation

1. Drop the `FortunePillars-*.jar` into your `plugins/` folder.
2. Restart the server.
3. Configure `plugins/FortunePillars/config.yml`.
4. Optional: set up maps (see [Maps](#maps)).

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/pp` | Root command: `game`, `queue`, `map`, `config` sub-commands. | — |
| `/pp forcestart` | Skip the queue and start a game with the players already in it. | `fortunepillars.forcestart` (op) |
| `/game start <mode> <x,y,z> <world> <players...>` | Manually start a game with the given mode at a location (aliases: `/pillar-peril`, `/match`, `/round`). | `fortunepillars.start` (op) |
| `/game stop [id]` | Force-stop a running game. | `fortunepillars.stop` (op) |
| `/game list [raw]` | List all running games (or just their IDs). | `fortunepillars.list` |
| `/game info <id>` | View details about a running game. | `fortunepillars.info` |
| `/pp queue join` | Join the queue (command queue method). Opens the map picker first. | — |
| `/pp queue leave` | Leave the queue. | — |
| `/pp queue admin list/add/remove/clear` | Administer the queue. | op |
| `/pp map setup <name>` | Start map setup: place your arena, then run save. | `fortunepillars.map` (op) |
| `/pp map save <name>` | Save the selected region as a schematic. | `fortunepillars.map` (op) |
| `/pp map paste <name>` | Paste a saved map for editing/verification. | `fortunepillars.map` (op) |
| `/pp map set spawn <name> [index]` | Set the (next) player spawn on the current map. | `fortunepillars.map` (op) |
| `/pp map set spectatorspawn <name>` | Set where spectators watch from. | `fortunepillars.map` (op) |
| `/pp map set deathheight <name> <y>` | Override the void death height for this map. | `fortunepillars.map` (op) |
| `/pp map list` | List all saved maps. | `fortunepillars.map` (op) |
| `/pp map info <name>` | Show a map's details. | `fortunepillars.map` (op) |
| `/pp map delete <name>` | Delete a saved map. | `fortunepillars.map` (op) |
| `/pp map reset <name>` | Reset a map's metadata (spawns, spectator spawn, ...). | `fortunepillars.map` (op) |
| `/pp config modify <path> <get\|set\|add\|remove> ...` | Read/modify the configuration in-game. | `fortunepillars.config` (op) |
| `/pp-config` | Manage the FortunePillars configuration (alias for the config sub-command). | `fortunepillars.config` (op) |

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `fortunepillars.start` | op | Start a game manually. |
| `fortunepillars.stop` | op | Stop a running game. |
| `fortunepillars.list` | everyone | List running games. |
| `fortunepillars.info` | everyone | View game details. |
| `fortunepillars.config` | op | Manage the configuration in-game. |
| `fortunepillars.map` | op | Create and manage maps. |
| `fortunepillars.forcestart` | op | Force-start a game from the queue. |

## Game Modes

| Mode | Description |
| --- | --- |
| `normal` | The classic Pillars of Fortune experience: a weighted mix of gear, blocks, food, utility and combat mobs. |
| `blocky` | Mainly building blocks, with the occasional piece of PvP gear. |
| `action` | Action-packed: multiple items per drop, lots of blocks and PvP gear, a high power-up rate. |
| `op` | Overpowered loot: netherite and diamond gear, boss and elite mob spawn eggs, enchanted apples, totems and elytra. |

## Modifiers

Modifiers are voted on or picked in the multi-modifier picker:

| Modifier | Description |
| --- | --- |
| `normal` | No special twist. |
| `rising-lava` | Lava rises from below, forcing everyone upward. |
| `tnt-falls` | Primed TNT falls onto the arena on a timer. |
| `speedrunner` | Items drop every 2 seconds. |
| `arrow-rain` | Arrows rain down over the whole arena on and off. |
| `lightning` | A random player is struck by lightning (damage + one random gear enchant). |
| `moonwalk` | Everyone gets jump boost and slow falling. |
| `chain-swap` | Every player's position shifts to the next player's spot. |
| `ablockalypse` | Random blocks rain down from the sky in small batches. |
| `lava-floor` | The block beneath players transforms through warning colors into lava. |
| `uhc` | Natural regeneration is disabled. |

## Loot System

- **Curated default pool** — when `items.pool` is empty, a built-in weighted pool is used:
  weapons and armor of every tier, tools, building blocks (including iron/diamond/gold blocks
  for crafting), food, utility items (ender pearls, buckets, torches), potions, suspicious stew,
  and combat spawn eggs. Override it entirely with `items.pool` entries (e.g. `diamond_sword: 5`
  makes diamond swords 5× as common).
- **Blacklist** — `items.blacklist` excludes operator-only items, horse armor, boss eggs
  (wither/elder guardian/warden) and peaceful mob eggs by default.
- **Item refinement** — crossbows drop loaded, potions (drinkable, splash and lingering) roll a
  random effect, suspicious stew gets a random effect, snowballs stay in small bundles and deal
  `items.snowball-damage` hearts on hit.
- **Enchanted gear** — `items.enchant-chance` (default 30%) of gear drops pre-enchanted with an
  enchant that actually applies.
- **Power-ups** — `items.power-up-chance` (default 10%) of drops are replaced by a useful item
  (golden apple, ender pearl, ...) or a glowing special item:
  - **Super Star** — right-click: absorbs the next 3 hits for 30 seconds.
  - **Fireball** — right-click: throws a blazing fireball.
  - **Aid Platform** — right-click: summons a slime platform below your feet.
- **TNT** — placing TNT always primes it with a 3-second fuse.

## Configuration

The main sections of `config.yml` (each is documented inline):

- **General** — `platform-height`, `platform-material`, `platform-distance-factor`,
  `enable-draws`, `death-height`, `respawn-at-config`, `player-spawn`, `ending-commands`,
  `time-after-game`, `winner-celebration-seconds`, `kill-credit-window`.
- **`queue`** — enabled, `min-players`/`max-players`, countdowns (`start-delay`,
  `start-delay-half`, `start-delay-full`), `method` (command/auto), default `mode`, `world`,
  `location`, `map-pool`, `default-item-time`, `pre-commands`/`post-commands`.
- **`modes`** — per-mode settings: `cooldown` (item drop interval), `time-limit`,
  `generator.horizontal` (circular/random) and `generator.vertical` (pillar/block),
  `visual.color`, `visual.show-scoreboard/actionbar/bossbar`.
- **`modifiers`** — per-modifier settings (interval, start delay, duration, ...).
- **`items`** — `blacklist`, `pool`, `cleanup-interval`, `avoid-held-slot`, `snowball-damage`,
  `power-ups`, `power-up-chance`, `double-drop-chance`, `enchant-chance`.
- **`border`** — invisible barrier cylinder around the play area (`radius`, `bottom-offset`,
  `top-offset`).
- **`menu`** — MiniMessage titles and lobby item names (`vote.title`, `map.title`,
  `multi.title`, `map-item.name`, `leave-item.name`).
- **`sound-effects`** — master toggle, cooldown and per-event toggles.
- **`disable-faststats`** — opt out of anonymous FastStats tracking (default off).

## Maps

1. `/pp map setup <name>` — starts setup mode (selection tools).
2. Build your arena and define spawns: `/pp map set spawn <name>` at each spawn point.
3. Optional: `/pp map set spectatorspawn <name>` and `/pp map set deathheight <name> <y>`.
4. `/pp map save <name>` — saves the schematic to `plugins/FortunePillars/maps/`.
5. Add the map to `queue.map-pool` or leave it empty to allow every saved map.

Each game re-pastes the schematic and restores it afterwards. Maps with fewer spawns than
players are skipped by the queue. Per-map `death-height` overrides the global one.

## Placeholders

With PlaceholderAPI installed: `%fp_playing%` — number of players currently in games.

## Localization

All messages live in `plugins/FortunePillars/lang/<locale>.properties`. Add a translation by
copying `en_US.properties` (shipped in the jar under `lang/`) and translating the values.
Players with a matching client locale get the translation automatically.

## Operations & Performance

Server tuning recommendations for running FortunePillars smoothly (adapted from Paper/Spigot
best practices; the authoritative reference is https://docs.papermc.io/performance):

- **JVM flags (Aikar's flags)** — add to your startup script:
  ```
  -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions
  -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40
  -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4
  -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90
  -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem
  -XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true
  ```
- **View/simulation distance** — 6–8 for both (`view-distance` and `simulation-distance` in
  `spigot.yml`/`paper-global.yml`). Minigame arenas are small; larger distances only waste CPU.
- **Chunk pre-loading** — the arena world gets written and restored per game; pre-generate the
  arena chunks at startup (e.g. `chunky` or `/pp map paste` once per map) so the first match
  doesn't trigger a chunk-generation spike.
- **`max-auto-save-chunks-per-tick`** — lower it (e.g. 1–2) if arena world saves cause tick
  spikes between matches.
- **Despawn ranges** — keep `entity-activation-range` and item despawn ticks moderate (vanilla
  defaults are fine); FortunePillars already cleans up its own drops/arrows/mobs after every match.
- **`optimize-explosions`** — enable in `paper-global.yml`; TNT falls and ablockalypse create
  many small explosions.
- **Restarts** — schedule weekly restarts; block tracking, FastStats buffers and the metric lists
  only reset on a fresh process.
- **Profiling** — install Spark (`/spark profiler`) to catch stalls: a game start that freezes
  the server for >1s is usually schematic pasting or chunk generation, a repeating stall during
  a match is usually an entity/particle storm.

## Building

```sh
./gradlew build
```

The jar lands in `build/libs/FortunePillars-0.1.jar`.

## Links

- Source: <https://github.com/SwapFlip/FortunePillars>
- Issues: <https://github.com/SwapFlip/FortunePillars/issues>