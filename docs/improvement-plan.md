# PillarPeril — Improvement Plan

**Decisions locked in (from user answers):**
- Scope: fix everything (gameplay P0/P1, security, memory/perf, performance, ops docs, features) **except** the crafting bypass.
- Keep spawn eggs. Keep the existing obtainable item set. Loot stays fully random.
- Balance direction: diamond/netherite gear common-ish in OP and action, weaker gear common in normal/blocky, with occasional rare strong drops.
- Add a **low-chance custom-item roll**: Super Star (nether star), Fireball, Aid Platform (slime), and a new **TNT** custom item.
- Plan file: `docs/improvement-plan.md`. Backup of the pre-change project lives in `Backup/pillarperil-backup-<timestamp>/`.

---

## Phase 1 — Gameplay fixes

### P0 — game-breaking
1. **Multi-modifier starts mid-vote** (`Game.kt:648-685`). Gate the item countdown/cage release so the fight cannot start while any player is still in the modifier picker. Countdown pause until `multiSelectionDone`; 60s hard timeout already exists.
2. **Floorless arena cages / overflow spawns fall to death** (`Cage.kt:154-173`, `QueueManager.kt:132-135`). Add a floor layer to `buildArenaCage` and/or a `started` guard in the void handler (`PlayerEvents.kt:232-242`) so nobody dies below `deathHeight` during the countdown.

### P1 — fairness
3. **TNT → custom item.** Remove TNT from the block pool (weight 0 in all profiles), add a new `Special("tnt", TNT)` to `SPECIALS` so it drops via the low-chance custom roll instead of as a common block.
4. **Gear weight inversion** (`LootWeights.kt:61-74`). Lower the unlisted-material fallback weight per category and raise explicitly-listed good gear: normal = weaker gear common / iron occasional; action = iron/diamond more common; OP = diamond common, netherite rare; blocky = mostly blocks, rare gear. Same pool, no items removed.

### P2 — arena integrity / hazards
5. **RisingLava unbounded** (`RisingLavaModifier.kt:130-142`). Cap `lavaY` at a safe max and decouple the rise from the item countdown so the queue can't grow unbounded (~72k entries) under Speedrunner.
6. **RisingLava + LavaFloor hole punching** (`RisingLavaModifier.kt:186-208`, `LavaFloorModifier.kt:80-84`). Make modifier teardown order-independent: track only lava the modifier itself placed; LavaFloor restores its own blocks.
7. **Border containment** (`GameBorder.kt:25-26`, `Game.kt:528-535`). Add a ceiling cap and eliminate/teleport-back players above the wall or outside the radius; anchor the wall to the arena floor on low maps.
8. **ArrowRain arrows collectible** (`ArrowRainModifier.kt:65-68`). Make them non-pickup-able.
9. **ChainSwap teleports into solids/void** (`ChainSwapModifier.kt:31-44`). Validate the destination (air, above min height) before swapping; skip if unsafe.

### P3 — polish
10. **BlockyGame TRIDENT start** (`BlockyGame.kt:18-24`). Replace TRIDENT with a mode-legal starter.
11. **Lightning skips armor + mis-credits** (`LightningModifier.kt:57-67`). Apply to a random armor slot; attribute the kill to the lightning source player.
12. **LavaFloor stage per-block, not per-player** (`LavaFloorModifier.kt:43-64`) so it's telegraphed; never convert border BARRIERs.
13. **Modifier delay anchoring** — start delays should anchor to fight start, not construction; cache `playArea()` per game instead of recomputing every tick.
14. **`modifiersByClass` stale in multi mode** (`Game.kt:51`, `:789-797`) — rebuild after `activateMultiSelection`.

## Phase 2 — Security
15. **Console command injection hardening** (`FortunePillars.kt:100-102`): validate/escape placeholder interpolation; restrict to game-generated values only.
16. **`/queue admin` permission** (`Commands.kt:239`): replace `isOp` with a real permission node (`fortunepillars.queue.admin`).
17. **Declare all commands in `paper-plugin.yml`** (`Commands.kt:49-55`).
18. **Guard `onDisable`** (`FortunePillars.kt:64-70`): per-game `runCatching` so one failure can't abort shutdown cleanup.
19. **`end()` cleanup ordering** (`Game.kt:866-914`): move the title loop inside the `try/finally`, guard per player.
20. **Guard config auto-reload** (`Configuration.kt:212-226`): `runCatching` around `load()`; validate range (chances 0–100, `min-players <= max-players`, `platform-height` within build height).
21. **Fix `crmapin` default** (`config.yml:90`) to a sane default spawn world.

## Phase 3 — Memory / performance bugs
22. **Metrics list leak** (`Metrics.kt:14-31`, `GameManager.kt:21-33`): flush/clear the FastStats lists even when `disable-faststats` is true (clear on a schedule regardless).
23. **Queue-disabled strand leak** (`QueueManager.kt:240-263`): remove the early-return so quit/leave always cleans snapshots/votes/cages.
24. **`schematicSelections` leak** (`Commands.kt:478`): purge stale/abandoned selections.
25. **Hot-path allocations** (`FortunePillarsExpansion.kt:18-24`, `GameManager.kt:42-43`): cache lists, return early, avoid per-poll rescans.

## Phase 4 — Performance pass
26. **Async schematic I/O**: `SchematicReader.read` / `SchematicSaver.write` off the tick thread; apply results sync.
27. **Chunk-batched pasting** (`MapPaster`): pre-load chunks (`getChunkAtAsync` + chunk tickets), paste chunk-by-chunk with a per-tick budget (mirror the existing border/lava batching); keep the 2M cap.
28. **Particle throttle**: Super Star swirl (`Game.kt:713-737`) every 2–3 ticks and/or reduced density; precompute coords.
29. **`teleportAsync`** and RegionScheduler/EntityScheduler adoption for Folia-readiness.
30. **Cache `playArea()`** per game (see #13).

## Phase 5 — Operational/server docs (write into `README` or `docs/`)
31. Spark profiling workflow; Aikar JVM flags; paper/spigot/server tuning (view/sim distance 6–8, `max-auto-save-chunks-per-tick`, despawn ranges, `optimize-explosions`, merge-radius, item despawn); arena chunk pre-loading at startup; weekly restarts.

## Phase 6 — Feature roadmap (optional, after the above)
32. Persistent stats/leaderboards (wins/kills/deaths), points store for cosmetics (cages, effects), team mode, new modifiers (shuffle/balanced/fragile/portals).

## Verification
- `./gradlew build` for the Kotlin/Paper plugin (`build.gradle.kts`, `settings.gradle.kts`).
- Manual play test per phase: multi-modifier vote (P0), loot distributions across all four modes, lava cap, border escape, queue toggle on/off, config reload with malformed YAML, FastStats on/off memory check (heap/`/spark`).