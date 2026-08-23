# Gameplay Overhaul — Issue List & Change Log

All requested gameplay changes, the exact code they touch, and the decisions taken.
Status column updated as items are implemented.

## Modes & Loot

| # | Request | Change | Files | Status |
|---|---------|--------|-------|--------|
| M1 | Action mode: unban netherite, elytra, totems, enchanted gapples, nether star | `actionProfile.banned` → `CUSTOM_ONLY` (TNT stays special-item-only) | `LootWeights.kt` | ✅ |
| M2 | Action mode: fast paced & flashy | Default cooldown 5s → 3s; every action drop bursts with firework-sparkle particles | `config.yml`, `PillarPlayer.kt`, `VersionCompat.kt` | ✅ |
| M3 | Action mode: 1 item per drop | Kept as-is (already 1) — confirmed with owner | — | ✅ |
| M4 | OP mode: don't ban nether star or TNT | `opProfile.banned` → empty; raw TNT drops as placeable block (placing still auto-primes, 3s fuse) | `LootWeights.kt` | ✅ |
| M5 | No illegal blocks ever (bedrock, command blocks, ...) | Already enforced: `UNOBTAINABLE_ITEMS` + `items.blacklist` + junk filter + feature-flag check run on top of any weights; also excluded from Block Rain rain-pool | existing filters | ✅ verified |

## Modifiers

| # | Request | Change | Files | Status |
|---|---------|--------|-------|--------|
| D1 | TNT Falls: spawn 4 TNT per round, not 3 | `per-drop` default 3 → 4 | `TntFallsModifier.kt`, `config.yml` | ✅ |
| D2 | Speedrunner: Speed + start kit | Speed I for the whole match (infinite duration, no particles) + 32 oak planks + gold helmet + leather chestplate + iron boots equipped at init | `SpeedrunnerModifier.kt` | ✅ |
| D3 | Arrow Rain: constant drizzle, capped | Removed burst windows; arrows fall continuously after start-delay, capped at `max-arrows` concurrent (default 120) | `ArrowRainModifier.kt`, `config.yml`, locale | ✅ |
| D4 | Lightning: half heart damage | `damage` default 4 → 1 (= half heart) | `LightningModifier.kt`, `config.yml` | ✅ |
| D5 | Lightning: never lethal | Targets with health ≤ damage are skipped (someone else gets struck instead) | `LightningModifier.kt` | ✅ |
| D6 | Lightning: enchant any item incl. offhand | Gear pool = storage + armor + offhand | `LightningModifier.kt` | ✅ |
| D7 | More info via actionbar, not chat | Lightning strike announcement → actionbar. Death messages stay in chat (owner decision) | `LightningModifier.kt` | ✅ |
| D8 | Moonwalk: feel exactly like moonwalk | Continuous whole-match low gravity: Jump Boost III + Slow Falling, reapplied every second from fight start (owner chose continuous over bursts) | `MoonwalkModifier.kt`, `config.yml`, locale | ✅ |
| D9 | Chain Swap: skip targets below y=32 | New `min-y` config (default 32); swap destinations below it are skipped | `ChainSwapModifier.kt`, `config.yml` | ✅ |
| D10 | Ablockalypse → "Block Rain", ALL blocks fall | Display name/description renamed (namespace stays `ablockalypse` for config compat); rain pool = every placeable block minus air/fluids/portals/fire/technical/illegal blocks | `AblockalypseModifier.kt`, `en_US.properties`, `config.yml` | ✅ |
| D11 | Lava Floor: timed stages | Rewritten to heat-clock model: standing starts a timer on that block → 5s yellow wool → +3s orange → +3s red → +3s lava; keeps cooking after you step off. Config keys `stand-time` / `stage-time` replace `interval` | `LavaFloorModifier.kt`, `config.yml`, locale | ✅ |
| D12 | UHC: 5 gapples start + healing drops | Everyone starts with 5 golden apples; ~20% of regular drops become a healing potion (10s Regeneration or Instant Health); specials/power-ups never converted | `UhcModifier.kt`, `config.yml`, locale | ✅ |

## Items & Fixes

| # | Request | Change | Files | Status |
|---|---------|--------|-------|--------|
| F1 | Fireball: reduce knockback | Explosion power 2.5 → 1.5 (Bukkit ties KB to power; crater shrinks too — accepted) | `PlayerEvents.kt` | ✅ |
| F2 | Snowball/Fireball must not drop during Lava Rises | While Rising Lava is active those two outcomes are re-rolled into other loot | `PillarPlayer.kt` | ✅ |
| F3 | Super Star makes players invisible — particles only | Investigated: shield code has NO invisibility (always was particles-only). Root cause: `INVISIBILITY` potions in the random loot potion pool. Removed invisibility from that pool so nobody can go invisible mid-fight | `SpecialItems.kt` | ✅ |

## Decisions locked with owner

- Moonwalk: continuous, not bursts
- Action: 1 item per drop (no multi-drops)
- Death messages: stay in chat
- Lightning damage: 1 (half heart)
- Block Rain exclusions: water, lava, command blocks, anything illegal

## Verification

- [x] `./gradlew build` passes
- [x] All changed defaults present in shipped `config.yml`
- [x] Locale strings updated for renamed/reworded modifiers
