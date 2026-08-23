# Minigame Best-Practices Research — Fortune Pillars (Scoreboard, Item Drops, Death, Game State, Polish)

Research of how popular Bukkit/Paper minigame plugins (BedWars1058, MBedwars, aSkyWars, SkywarsReloaded, BedWars2023) and Hypixel handle the five existing Fortune Pillars systems. Each section: current behavior → findings (with URLs) → actionable improvements.

## 1. Scoreboard

**Current (Fortune Pillars):** One per-player `SimpleScoreboard` (libpg) created in `PillarPlayer.init` via `game.scoreboard?.invoke(this)`; entries show mode/name/time/kills. A separate `SimpleActionBar` + Adventure `BossBar` show the item countdown (`Game.kt:155-170`).

**Findings:**
- BedWars1058 scoreboard re-write spec: packet-based, async task; config `title-refresh-interval: 2` ticks, `placeholders-refresh-interval: 20` ticks, `task-type: async`. Separate sidebars per game state (`lobby`, `waiting`, `starting`, `playing`, `restarting`) **and per player type** (`sidebar.Default.playing.eliminated` vs `sidebar.Default.playing` for active players). Ending-phase sidebar shows the winner team. Placeholders `{teamStatus}`, `{teamName}`, `{teamColor}`, `{teamLetter}`.
  - https://gitlab.com/andrei1058/BedWars1058/-/issues/410
  - https://www.spigotmc.org/resources/bedwars1058-opensource.97320/update?update=524735
- Live scoreboard YAML (per arena group, e.g. `4v4v4v4`): header `"&f&lBED WARS"`, `"{date}"` line, team status lines like `"{TeamRedColor}&lB&f {TeamRedName}&f: {TeamRedStatus}"`, footer `"{server_ip}"` — https://github.com/Melon-Oof/andreidocs/blob/main/docs/BedWars1058/configuration/language-configuration.md
- MBedwars: configurable `scoreboard-refreshrate` (ticks), layout per game state — https://wiki.mbedwars.com/en/Configuration/ScoreboardLayouts
- SkywarsReloaded: five named boards (`waitboard`, `waitboard-countdown`, `playboard`, `endboard`, `lobbyboard`) — https://www.spigotmc.org/resources/skywarsreloaded-updated-recoded-1-21-support-new-decentholograms-supports-all-mc-versions.69436/
- aSkyWars boards: per-state with Kills / Alive / Next Event / Map / Mode; configurable scroller in scoreboard config — https://builtbybit.com/resources/askywars.43528/
- TAB plugin: dynamic line hiding + number formatting, per-page boards — https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Scoreboard

**Actionable improvements (to existing system):**
1. Split the single board into 3 states: **waiting/queue** (players, votes, mode, map), **playing** (mode, time, kills, alive), **end** (winner, top kills). Swap the `SimpleScoreboard` content on state transition instead of one static layout.
2. On elimination, switch the player to a spectator variant of the playing board (per BedWars `eliminated` sidebar path): hide their own kills/time, show `Watching: <target>` + alive count.
3. Push scoreboard entry updates on events (kill, time tick 1s) rather than polling every tick; keep title refresh at ~2 ticks, values at 20 ticks (per BedWars #410 async spec).
4. Use dynamic line hiding (TAB pattern) so late joiners/spectators never see stale lines.

## 2. Item Drop / Kit Economy

**Current:** `PillarPlayer.giveItems` (PillarPlayer.kt:48-64) plays `Sound.ENTITY_ITEM_PICKUP` @ 0.75 vol for every item, gated by `Configuration.soundEffectsItem`. Countdown feedback: `UI_BUTTON_CLICK` when `itemCountdown <= soundEffectsCooldown` (Game.kt:371-372), gradient actionbar, bossbar whose `getColor` maps urgency to **BLUE** (`left < 0.2`, Game.kt:54-59).

**Findings:**
- BedWars1058 sounds config — the established mapping for item/event cues: generator item drop uses pickup sound; game-end `ITEM_TRIDENT_THUNDER`; kill `ENTITY_EXPERIENCE_ORB_PICKUP`; bed-destroy `ENDER_DRAGON_GROWL` + `WITHER_DEATH`; player re-spawn `BLOCK_SLIME_BLOCK_FALL`; separate `next-event` sounds for diamond/emerald upgrades — https://github.com/Melon-Oof/andreidocs/blob/main/docs/BedWars1058/configuration/sounds-configuration.md
- Hypixel's classic "ding" for XP/points is `ENTITY_EXPERIENCE_ORB_PICKUP`; community discussion confirming it — https://hypixel.net/threads/what-is-the-skill-xp-gain-sound.5559952/
- aSkyWars: rarity-flavored feedback — win animations, kill effects, victory dances, death cry; "double kill" chains — https://builtbybit.com/resources/askywars.43528/
- Kill-effect particles on death position (redstone block-break style) — https://hypixel.net/threads/skywars-kill-effect.4885689/
- BedWars1058-richgenerators issue: spawns default to *no* sound; plugins add a "pop" (pickup sound) on item spawn — https://gitlab.com/bedwars-addons/bedwars1058-richgenerators/-/issues/3
- BossBarTimer: countdown bossbar shifts yellow → red as time runs out; tick sounds on final seconds; finish sound `UI_TOAST_CHALLENGE_COMPLETE` — https://modrinth.com/plugin/bossbartimer

**Actionable improvements:**
1. **Tier item feedback by rarity** based on material class, using pitch variation (Hypixel-style distinct cues):
   - Common (wood/stone/leather/iron): keep `ENTITY_ITEM_PICKUP` @ 0.75/1.0 (unchanged).
   - Rare (gold/golden, diamond tools): `ENTITY_PLAYER_LEVELUP` @ 1.0/1.2 + gold actionbar `You got <item>!`.
   - Legendary (netherite/emerald/trident): title `LUCKY!` (times 10 40 10) + `TOTEM_OF_UNDYING` + totem particles.
2. **Fix the bossbar color inversion** (Game.kt:54-59): urgent (`left < 0.2`) should be **RED**, relaxed PINK — i.e. PINK→GREEN→YELLOW→RED as time runs out (BossBarTimer convention). Current map gives *blue* at maximum urgency.
3. Keep the cooldown `UI_BUTTON_CLICK`, but add `UI_TOAST_CHALLENGE_COMPLETE` at the exact drop moment.

## 3. Death & Elimination

**Current:** `PlayerEvents.onPlayerDeath` (PlayerEvents.kt:17-24) credits only `event.player.killer` (null for void/fall/TNT/arrow? — arrows set killer, but void/fall never do). Void death = `PlayerMoveEvent` y < `deathHeight` sets health 0 → death event with `DamageCause.VOID` and no credit. `Game.eliminate` (Game.kt:318-326) runs `onPlayerDeath` hooks + spectator transition. No elimination announcement titles or kill feedback sounds.

**Findings:**
- BedWars1058 `LastHit.java`: `ConcurrentHashMap<UUID, LastHit>` mapping victim → {damager entity, timestamp}; death credit falls back to the last damager within a time window — https://github.com/andrei1058/BedWars1058/blob/master/bedwars-plugin/src/main/java/com/andrei1058/bedwars/arena/LastHit.java
- DeepWiki kill-cause mapping: `VOID` credited to last-hitter within **15s**, `FALL` within **10s**; arrows credit the shooter; bow-hit feedback; respawn invulnerability; arena cleanup/restore after end — https://deepwiki.com/andrei1058/BedWars1058/4.6-event-processing-and-game-mechanics
- `PlayerKillEvent.PlayerKillCause` enum: `PVP`, `PLAYER_PUSH` (knocked into void), `PLAYER_SHOOT`, `EXPLOSION`, `VOID`, `PLAYER_DISCONNECT`, plus `*_FINAL_KILL` variants and `isFinalKill()` — https://javadocs.tomkeuper.com/com/tomkeuper/bedwars/api/events/player/PlayerKillEvent.PlayerKillCause.html
- Paper gotcha: `killer` is reset on respawn — capture it in the death event scope, don't store it — https://github.com/PaperMC/Paper/issues/8465
- aSkyWars: `YOU DIED` title for victim, kill messages with weapon + coins breakdown, double-kill chains, spectator chat format — https://builtbybit.com/resources/askywars.43528/
- MBedwars spectator hotbar: change-speed / next-round / leave items — https://wiki.mbedwars.com/en/Configuration/SpectatorHotbar

**Actionable improvements:**
1. Add last-hit tracking: `PillarPlayer.lastHit: Pair<Player, Long>?` set in an `EntityDamageByEntityEvent` listener (record damager + timestamp; refresh on every hit). Death credit logic: `killer != null` → killer; else if cause is `VOID` (15s window) or `FALL`/`SUFFOCATION` (10s window) → last-hit damager; else no credit. (BedWars `LastHit` + DeepWiki windows.)
2. Knock-off credit: `PLAYER_PUSH` — because Fortune Pillars has no PvP damage, record *velocity-induced* last-hit from any damage event so knock-offs into the void get credited.
3. Arrow kills: use `projectile.shooter` when killer is null (bedwars `PLAYER_SHOOT`).
4. Elimination announcement: victim gets title `ELIMINATED` (10 40 10) + low `GHAST_WARN`; killer gets actionbar `+1 Kill` + `ENTITY_EXPERIENCE_ORB_PICKUP` (BedWars kill sound). Play killer feedback in `onPostPlayerDeath` (after death screen), per BedWars event split.
5. Multi-kill chain: if same killer scores 2 kills within ~5s → `DOUBLE KILL!` title (aSkyWars pattern).

## 4. Game State (end + countdown/grace)

**Current:** End titles per `EndingCause` (Game.kt:393-409) with adventure default times (10/70/20); `ending-commands` config hook; modifier `onEnd`; cleanup. Queue countdown ticks `UI_BUTTON_CLICK`; cage release happens when `itemCountdown` hits 0 (first item cycle, Game.kt:359). No "3,2,1,GO" sequence and **no no-PvP grace** — cages are glass so melee is blocked, but TNT/explosion/border damage can hit caged players.

**Findings:**
- BedWars1058 countdown titles: config `arena-start-countdown-title-[second]` with `{second}` placeholder, shown when `currentSecond % 10 == 0 || currentSecond <= 5` — the canonical rule — https://github.com/Melon-Oof/andreidocs/blob/main/docs/BedWars1058/configuration/language-configuration.md
- Countdown title timing: `/title @a times 0 20 0` for 3→2→1→GO; ~40-tick fade-in for cinematic — https://www.minecraftmaps.com/tools/title-command-generator
- Grace period: Hypixel BedWars-style initial no-PvP; BedWars1058 cancels damage while arena not "playing", respawn invulnerability ~4s — https://deepwiki.com/andrei1058/BedWars1058/4.6-event-processing-and-game-mechanics ; https://hypixel.net/threads/bedwars-grace-period.5737084/
- End phase: BedWars ending sidebar shows winner team; game-end sound `ITEM_TRIDENT_THUNDER`; winners celebrate (fireworks); stats saved on `GameEndEvent`; world restored on next start (arena persists 10-15s for rewards) — https://github.com/Melon-Oof/andreidocs/blob/main/docs/BedWars1058/configuration/sounds-configuration.md ; https://deepwiki.com/andrei1058/BedWars1058/4.6-event-processing-and-game-mechanics
- Firework celebration plugins pattern — https://modrinth.com/plugin/ezcountdown ; Hypixel victory dances — https://hypixel.fandom.com/wiki/Bed_Wars/Cosmetics
- aSkyWars `endboard` scoreboard with winner — https://builtbybit.com/resources/askywars.43528/

**Actionable improvements:**
1. **Grace phase:** cancel `EntityDamageByEntityEvent` + TNT/explosion damage to players while `itemCountdown > 0` (cages down); release with `GO!` title (0 20 0) + `BLOCK_SLIME_BLOCK_FALL` (BedWars re-spawn sound).
2. **Queue countdown:** show `{second}` titles per the BedWars rule (`% 10 == 0 || <= 5`) with `times 0 20 0`; final second + GO sound.
3. **End:** set explicit `Title.Times`: winner `40 100 40` (cinematic) with subtitle + fireworks burst at winner location; losers `10 40 10`; global game-end sound `ITEM_TRIDENT_THUNDER` (BedWars). Swap scoreboard to end state (winner + top-3 kills) before cleanup.
4. **Stats order:** document/ensure save-stats → `ending-commands` → world cleanup ordering; keep arena entities alive ~10s post-end (BedWars "restart" delay) so kills/titles render.

## 5. Sound & Polish

**Current:** `playSoundSafe(sound, volume, pitch, requirement)` util gated by config; in use: `UI_BUTTON_CLICK` (countdown), `ENTITY_ITEM_PICKUP` (items); titles only (no sounds) for end and border shrink (`BorderShrinksModifier.kt:53`).

**Findings:**
- BedWars1058 sound map (the go-to reference for minigame cues): game-end `ITEM_TRIDENT_THUNDER`, kill `ENTITY_EXPERIENCE_ORB_PICKUP`, bed-destroy `ENDER_DRAGON_GROWL`+`WITHER_DEATH`, re-spawn `BLOCK_SLIME_BLOCK_FALL`, `next-event` sounds — https://github.com/Melon-Oof/andreidocs/blob/main/docs/BedWars1058/configuration/sounds-configuration.md
- Hypixel "ding" = `ENTITY_EXPERIENCE_ORB_PICKUP` — https://hypixel.net/threads/what-is-the-skill-xp-gain-sound.5559952/
- Warning announcements pattern (title + subtitle + pre-announce timer; border/refill warnings red) — aSkyWars: https://builtbybit.com/resources/askywars.43528/
- TNT: `ENTITY_TNT_PRIMED` hiss plays on priming (vanilla), so re-play it at spawn + red warning title for incoming TNT; particles for area effects use `Particle` API (e.g. `DRIP_LAVA`/`FLAME` along rising lava, smoke on TNT landing).
- Sound name reference (all versions incl. 1.20.4 renames) — https://gist.github.com/Andre601/1ab3b4fabd0010ae241156333491c379 (moved: https://andre601.ch/Spigot-Sounds)
- Death messages: cause-specific, killer/victim variants — https://www.spigotmc.org/resources/customdeathmessages-cdm.69605

**Actionable improvements (per modifier/system):**
1. `RisingLavaModifier`: pre-rise warning at 5s — title `Lava rises!` (5 20 5) + `ENDER_DRAGON_GROWL` (dramatic) or `GHAST_WARN`; spawn `DRIP_LAVA`/`FLAME` particles along the rising surface.
2. `BorderShrinksModifier` (BorderShrinksModifier.kt:53): keep title, add `ENDER_DRAGON_GROWL` on shrink + pre-announces at 30s and 10s (SkywarsReloaded announceTimer pattern).
3. `TntFallsModifier`: play `ENTITY_TNT_PRIMED` when dropping, red `TNT Incoming!` warning title (5 20 5), smoke particles at landing.
4. Queue: `UI_BUTTON_CLICK` already correct; add final-seconds tick (`BLOCK_NOTE_BLOCK_PLING`).
5. Kill: killer `ENTITY_EXPERIENCE_ORB_PICKUP`; win: `ITEM_TRIDENT_THUNDER` + fireworks; pitch variation for rarity cues (common 1.0 / rare 1.2 / legendary 1.5).

## Title sequence reference (used throughout)
- Countdown ticks / GO: `times 0 20 0`
- Fast alerts (warnings, TNT): `5 20 5`
- Standard announcements (elimination, lucky item): `10 40 10`
- Cinematic (win): `40 100 40`; default announcements: `10 70 20` (adventure default, current behavior)

## Sources (14)
1. https://gitlab.com/andrei1058/BedWars1058/-/issues/410
2. https://www.spigotmc.org/resources/bedwars1058-opensource.97320/update?update=524735
3. https://github.com/Melon-Oof/andreidocs/blob/main/docs/BedWars1058/configuration/language-configuration.md
4. https://github.com/Melon-Oof/andreidocs/blob/main/docs/BedWars1058/configuration/sounds-configuration.md
5. https://github.com/andrei1058/BedWars1058/blob/master/bedwars-plugin/src/main/java/com/andrei1058/bedwars/arena/LastHit.java
6. https://deepwiki.com/andrei1058/BedWars1058/4.6-event-processing-and-game-mechanics
7. https://javadocs.tomkeuper.com/com/tomkeuper/bedwars/api/events/player/PlayerKillEvent.PlayerKillCause.html
8. https://wiki.mbedwars.com/en/Configuration/ScoreboardLayouts
9. https://wiki.mbedwars.com/en/Configuration/SpectatorHotbar
10. https://www.spigotmc.org/resources/skywarsreloaded-updated-recoded-1-21-support-new-decentholograms-supports-all-mc-versions.69436/
11. https://builtbybit.com/resources/askywars.43528/
12. https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Scoreboard
13. https://www.minecraftmaps.com/tools/title-command-generator
14. https://modrinth.com/plugin/bossbartimer
15. https://github.com/PaperMC/Paper/issues/8465
16. https://gist.github.com/Andre601/1ab3b4fabd0010ae241156333491c379
17. https://hypixel.net/threads/bedwars-grace-period.5737084/
18. https://hypixel.fandom.com/wiki/Bed_Wars/Cosmetics
