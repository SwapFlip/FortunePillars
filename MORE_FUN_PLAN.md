# FortunePillars — "Make It More Fun" — Research & Implementation Plan

*Server target: Paper 1.20.4 (clients may be newer). Keep all code 1.20.4-safe; reuse `VersionCompat` for any cross-version calls. Build: `./gradlew build -q`.*
*Status: research + plan complete. Implementation is phased (0 → 1 → 2 → 3).*

---

## 1. Research — Codebase Catalog

### 1.1 Core loop (`game/Game.kt`)
- `init()` (`:389`) saves world gamerules, builds `PillarPlayer`s, resolves item pool via `buildItems()` (`:480`), builds arena, starts bossbar/countdown.
- Phases: cage/countdown (`started==false`, round timer frozen) → fight (`releaseCages()` `:820`, `started=true`) → end/celebration.
- `tick()` (`:699`): empty-roster force-end, multi-mode picker freeze, item countdown (opens cages at 0 else drops), `timeLeft` only while `started`, out-of-bounds elimination, Super Star particle spiral.
- `EndingCause` (`:63`): `FORCE, TIME_OVER, LAST_STANDING, DRAW, ERROR`. `end()` (`:936`) shows titles + kills leaderboard + runs `Configuration.endingCommands` (placeholders `%id% %mode% %players% %cause% %world% %x% %y% %z%`) + `cleanup()` (`:1046`).

### 1.2 Modes (`game/mode/*.kt`)
All extend `Game`; differ only by `GameInfo` (loot profile/power-ups). `Registry.modes` (`:29`).
| Mode | Loot | Notes |
|---|---|---|
| Normal | `LootWeights.normal` | bans diamond/netherite/endgame/TNT |
| Action | `LootWeights.action` | nothing banned; `powerUpChance=30` |
| Op | `LootWeights.op` | nether stars + raw TNT drop; TNT auto-primes 3s |
| Blocky | `LootWeights.blocky` | ~95% blocks; starts with random melee item |

`GameInfo` fields: `itemCountdown, timeLimit, accentColor, showScoreboard/showBossBar, horGen/vertGen, lootWeights, dropCount(=1), powerUpsEnabled, powerUpChance, enchantChance(≤10), specialChance(≤2)`. All live in `modes/<ns>.yml`.

### 1.3 Loot / specials / power-ups
- `PillarPlayer.giveItems()` (`:77`): draws `dropCount` materials without replacement; per item rolls **Special** (`specialChance` ≤2%, independent of power-ups), **Power-up** (`powerUpsEnabled && powerUpChance`), or **Regular** (`maybeEnchant` + `refine`). Each item passes every modifier's `onItemReceive`.
- **Specials** (`SpecialItems.kt`, `SPECIALS`): `SUPER_STAR` (Nether Star, 2-charge/30s shield, `PlayerEvents.kt:141`), `FIREBALL` (SmallFireball, 1.5-power fireless explosion, `:148`), `AID_PLATFORM` (Slime Block 3×3, `:154`), `TNT` (place-to-prime 3s fuse, `GameEvents.kt:119`). Specials get glint + `KEY` PDC tag.
- **Power-ups** (`randomPowerUp`): from mode config `power-ups.items`/`potions`, else legacy fallback. Tagged `POWER_UP_KEY` so UHC skips them.

### 1.4 Scoring / kills / death
- `PillarPlayer.kills` (`:33`). Kill credit (`PlayerEvents.onPlayerDeath` `:50`): direct killer, else `lastDamagedBy` within `Configuration.killCreditWindow` (15s) and still alive. Indirect void/leave-world also credited.
- `eliminate()` (`:611`): removes player, `win = players.size <= 1`, flags `winResolutionPending` (19-tick delay, `ELIMINATION_DELAY_TICKS`) to avoid premature force-end. Draw logic + winner celebration (fireworks, `winnerCelebrationSeconds`).

### 1.5 Modifiers (`game/modifier/*.kt`) — extension pattern
`GameModifier` abstract (`GameModifier.kt:8`): `init(), customBuild(), tick(tick), onItemCycle(), onItemReceive(item), onPlayerDeath(p), onPostPlayerDeath(p), onEnd()`. Registered in `Registry.modifiers` (`:35`) + advertised in `QueueEvents.typeOrder` (`:62`) + `typeMaterials` (`:76`) + `modifier.<ns>.name/.description` locale. Config via `ModifierConfigs` (auto-generates file if in `MODIFIERS` list, `:14`).
- 11 existing: normal, lightning, tnt-falls, lava-floor, arrow-rain, moonwalk, ablockalypse, speedrun, uhc, lava-rises, chain-swap. (RisingLava reads LavaFloor's `ownedPositions` to avoid wiping its blocks — pattern for stacking hazards.)

### 1.6 Player experience / UI / queue / config
- Queue (`QueueManager.kt`): join→snapshot→cage→vote (mode/type/time/map)→dynamic countdown→`check()` (`:495`) builds game. Scoreboards via `QueueScoreboards.kt` + `ScoreboardTemplates.kt`.
- Spectators (`SpectatorManager.kt`, `SpectatorEvents.kt`): `spectator.menu.title` compass implies a player-tracking menu that is **currently unwired**.
- HUD: titles at start/end; action bars for modifiers/queue; bossbar tracks item countdown only. **Dead keys**: `actionbar.now`, `game.start.countdown`, `queue.item.*`.
- `en_US.properties`: full message set; inconsistencies (win lacks `.stats`; stray indents; trailing space in `queue.countdown`).
- `Configuration.kt`: grouped knobs (platform/queue/scoreboard/sound/items/border). `validateValues` (`:270`) clamps.
- `FortunePillarsExpansion.kt` already provides `%fp_playing%` (PlaceholderAPI). **Vault / LuckPerms not referenced** → optional hooks.
- **Gaps (fun):** no progression/streaks/leaderboard; no achievements/cosmetics; limited sound juice; no kill-feed (user said skip); no animated countdown; no play-again; no live map-vote results; no rampage cue; no ambient music; thin spectator menu; inconsistent end stats.

## 2. Research — Cosmetics / Particle Libraries (web)
- **EffectLib** (elBukkit, github.com/elBukkit/EffectLib) — **MIT license**, library of complex particle effects (spheres, stars, trails, text, explosions, vortex…). Shadeable via Gradle (`implementation 'com.elmakers.mine.bukkit:EffectLib:10.2'`, relocate). Best fit for rich victory dances / trails. ✅ usable.
- **ParticleLib** (ByteZ1337) — multiversion 1.8–1.19.3; may lack 1.20.4 coverage. ⚠️ secondary.
- **CosmeticCore** (BianaryAssasin) — "All rights reserved" → ❌ not usable.
- **PlayerParticles** (Spigot) — plugin, not a shadeable lib, unclear license → ❌ avoid.
- **Native alternative:** the plugin already uses `World.spawnParticle` for the Super Star shield (`Game.kt:791`). Trails/cage-themes/victory bursts can be done natively with `spawnParticle` — lean, 1.20.4-safe, no deps. **Recommendation:** implement cosmetics natively; optionally shade EffectLib (MIT) only if fancier victory dances are wanted.

## 3. Refined Plan (decisions applied)
**Solo FFA only · file store + optional Vault (economy) + LuckPerms (ranks) + PlaceholderAPI (wired) · phased · open-source code OK only if MIT/Apache (EffectLib).**

### Phase 0 — Quick wins
1. ❌ Do **not** fire the "New items" action bar (redundant with boss bar).
2. Fix **win end-screen**: add `info.end.win.stats` + render Top Players panel for wins (`Game.kt` `end()`).
3. **Play-Again**: give players a clickable item / `/pp queue join` on end (`Game.end` + `QueueManager.add`).
4. **Live map-vote results**: per-map counts in queue scoreboard/actionbar (reuse `QueueManager` aggregation).
5. Locale hygiene: fix `queue.leave.not_in_game` indent, `queue.countdown` trailing space, wire/remove dead keys.
6. Comment fix: Super Star is 2 charges — fix stale "3 charges" comments (`PillarPlayer.kt`/`Game.kt`).

### Phase 1 — Juice & feedback
- **Animated countdown**: `3 / 2 / 1 / GO` title sequence in cage phase (`Game.tick` before `releaseCages`).
- ❌ No kill feed.
- **Personal combat feedback**: "You eliminated X" action bar + sound for killer; death title-flash + sound for victim; throttled hit sound/particles on `EntityDamageByEntityEvent`.
- **Richer celebration**: fireworks + gradient title for **top 3** (crown 1st, recognition 2nd/3rd).
- **Spectator tracking menu**: wire `spectator.menu.title` compass → skull menu of alive players (`SpectatorEvents`).
- **Rampage cue at 3 kills** (action bar + sound).
- **Ambient queue music** (config toggle, soft loop).

### Phase 2 — New gameplay
- **Modifier `mob-wave`**: spawn hostile mobs falling from sky across map; **no fall damage on first landing** (track entity in a set; cancel first `EntityDamageEvent` FALL damage; thereafter normal). Config: `interval, start-delay, types, cap, height`.
- **Modifier `shrinking-world`**: radius shrinks over time with a **clearly visible red-particle boundary** as it closes. Config: `start-delay, shrink-rate, min-radius`.
- **Special `Levitation Feather`** only (right-click → timed Levitation; add to `SpecialItems.SPECIALS` + `PlayerEvents.onInteract`).
- *Dropped per your call:* magnet, discombobulate, double-drop, gravity, grappling, invis, extra-heart, decoy, boombox.

### Phase 3 — Meta & progression
- **`util/PlayerStats.kt`**: flatfile JSON per UUID (games, wins, losses, kills, deaths, current/best streak, playtime); load on join, save on `Game.end`.
- **Leaderboard**: `/pp stats [player]`, `/pp top [wins|kills|streak]`; **PlaceholderAPI** placeholders for self-built boards: `%fp_top_wins_name_1%`/`%fp_top_wins_1%`, `%fp_top_kills_name_1%`/`%fp_top_kills_1%`, `%fp_top_streak_name_1%`/`%fp_top_streak_1%` (top N, e.g. `_1`..`_3`), plus per-player `%fp_wins% %fp_kills% %fp_streak% %fp_rank%`. Extend `FortunePillarsExpansion`.
- **Achievements**: milestone toasts (First Win, Rampage, Survivalist…) + reward hook.
- **Rewards**: Vault coins on win/participation if present, else config commands; configurable amounts.
- **Ranks**: optional LuckPerms group by tier/streak, graceful no-op if absent.
- **Cosmetics** (cosmetic-only, file-tracked unlocks): particle **trails**, **cage themes**, **victory effects** — native `spawnParticle` (1.20.4-safe); optionally shade **EffectLib (MIT)** for fancier dances.
- **Config**: `stats.yml` / `rewards.yml` / `achievements.yml` with enable flags + API toggles.

## 4. Architecture & verification
- New modifiers/specials plug into existing `GameModifier` / `SpecialItems` extension points.
- External integrations behind a **`Hooks` facade** (mirror `FortunePillars.kt:52` PAPI pattern) — missing Vault/LuckPerms degrade gracefully.
- Persistence: `data/stats/*.json`, versioned map, no migrations.
- Verify each phase: `./gradlew build -q` + manual playtest (queue→vote→play→win→stats→reward→leaderboard).
- Order: **Phase 0 → 1 → 2 → 3**, each verified before next.
