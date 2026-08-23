package com.swapflip.fortunepillars.util

import com.marcpg.libpg.config.*
import com.marcpg.libpg.storing.Cord
import com.marcpg.libpg.util.BasicOptional
import com.marcpg.libpg.util.toLocation
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.Registry
import com.swapflip.fortunepillars.game.mode.NormalGame
import com.swapflip.fortunepillars.game.util.GameManager
import org.bukkit.*

object Configuration : Config(PaperConfigProvider()) {
    override val versionHistory: List<ConfigVersion> = listOf(
        ConfigVersion(id = 2),
        ConfigVersion(id = 3),
        ConfigVersion(id = 4),
        ConfigVersion(id = 5),
        ConfigVersion(id = 6),
        ConfigVersion(id = 7),
        ConfigVersion(id = 8),
        ConfigVersion(id = 9),
        ConfigVersion(id = 10),
    )

    override val version: Int = 10

    var platformHeight by double("platform-height", 200.0)
    var platformMaterial by custom("platform-material", PPEntryTypes.minecraftRegistry(org.bukkit.Registry.MATERIAL), Material.BEDROCK)
    var platformDistanceFactor by double("platform-distance-factor", 10.0)
    var enableDraws by boolean("enable-draws")
    var endingCommands by custom("ending-commands", PPEntryTypes.placeholder.list, listOf())
    var respawnAtConfig by boolean("respawn-at-config")

    var spawnGameMode by enum<GameMode>("player-spawn.game-mode", GameMode.ADVENTURE)
    var spawnWorld by custom("player-spawn.world", PaperEntryTypes.world, BasicOptional.ofNull())
    var spawnCord by custom("player-spawn.location", ExtendedEntryTypes.cordMap, Cord(0.0, -64.0, 0.0))

    var queueEnabled by boolean("queue.enabled")
    var queueMinPlayers by int("queue.min-players", 2)
    // Default 8: a standard arena map has 8 spawns, so the queue caps at a full map by default.
    var queueMaxPlayers by int("queue.max-players", 8)
    var queueStartDelay by int("queue.start-delay", 60)
    var queueStartDelayHalf by int("queue.start-delay-half", 30)
    var queueStartDelayFull by int("queue.start-delay-full", 5)
    var queueMethod by enum<QueueMethod>("queue.method", QueueMethod.COMMAND)
    var queueMode by custom("queue.mode", PPEntryTypes.registry { Registry.modes }, NormalGame)
    var queueWorldName by custom("queue.world", PPEntryTypes.placeholder, PlaceholderNameGetter("PillarPeril"))
    var queueCord by custom("queue.location", ExtendedEntryTypes.cordMap, Cord(0.0, -64.0, 0.0))
    var queuePreCommands by custom("queue.pre-commands", PPEntryTypes.placeholder.list, listOf())
    var queuePostCommands by custom("queue.post-commands", PPEntryTypes.placeholder.list, listOf())
    var queueMapPool by custom("queue.map-pool", BaseEntryTypes.string.list, emptyList())
    var queueDefaultTime by int("queue.default-item-time", 10)

    // Soft background music while players wait in the queue. Off by default; when enabled a low-volume
    // music disc track loops to everyone queued. The sound name must be a valid org.bukkit.Sound key.
    var queueAmbientMusic by boolean("queue.ambient-music.enabled", false)
    var queueAmbientMusicSound by string("queue.ambient-music.sound", "MUSIC_DISC_CHIRP")
    var queueAmbientMusicVolume by double("queue.ambient-music.volume", 0.3)

    var scoreboardTitle by string("scoreboard.title", "<bold><gradient:#71CCF8:#FC91EC:#F87171>Fortune Pillars")
    var scoreboardLines by custom("scoreboard.lines", BaseEntryTypes.string.list, emptyList())
    var scoreboardEnabled by boolean("scoreboard.enabled", true)
    var scoreboardUpdateInterval by int("scoreboard.update-interval", 5)
    var scoreboardShowNumbers by boolean("scoreboard.show-numbers", false)
    // How the <ping> placeholder renders: "ms" = "23ms", "plain" = "23", "colored" = green/yellow/red
    // by quality (thresholds below). Anything unknown falls back to "ms".
    var scoreboardPingStyle by string("scoreboard.ping-style", "ms")
    var scoreboardPingGoodMs by int("scoreboard.ping-good-ms", 60)
    var scoreboardPingWarnMs by int("scoreboard.ping-warn-ms", 120)

    var queueScoreboardEnabled by boolean("queue-scoreboard.enabled", true)
    var queueScoreboardTitle by string("queue-scoreboard.title", "<bold><gradient:#71CCF8:#FC91EC:#F87171>Fortune Pillars")
    var queueScoreboardLines by custom("queue-scoreboard.lines", BaseEntryTypes.string.list, emptyList())
    var queueScoreboardUpdateInterval by int("queue-scoreboard.update-interval", 20)
    var queueScoreboardShowNumbers by boolean("queue-scoreboard.show-numbers", false)

    var soundEffectsEnabled by boolean("sound-effects.enabled", true)
    var soundEffectsCooldown by int("sound-effects.cooldown", 3)
    var soundEffectsItem by boolean("sound-effects.item", true)

    // Menu titles and lobby item names, rendered through MiniMessage so they support colors,
    // gradients and formatting (e.g. "<gold><bold>Select a Map").
    var menuVoteTitle by string("menu.vote.title", "Vote for Game Mode")
    var menuMapTitle by string("menu.map.title", "Select a Map")
    var menuMultiTitle by string("menu.multi.title", "Choose Modifiers")
    var menuMapItemName by string("menu.map-item.name", "<gold><bold>Vote Menu")
    var menuLeaveItemName by string("menu.leave-item.name", "<red><bold>Leave Queue")

    // Custom modifier names and descriptions shown in the vote menu. Supports full MiniMessage
    // formatting. Keys are the modifier namespace (e.g. "lava-rises", "speedrun").
    // When empty, the locale strings are used as fallback.
    val modifierCustomNames: Map<String, String> get() = ((provider as? PaperConfigProvider)?.configuration?.getConfigurationSection(ConfigPaths.MODIFIERS_CUSTOM_NAMES)?.getValues(false) ?: emptyMap())
        .mapNotNull { (name, value) -> (value as? String)?.takeIf { it.isNotEmpty() }?.let { name to it } }
        .toMap()

    val modifierCustomDescriptions: Map<String, String> get() = ((provider as? PaperConfigProvider)?.configuration?.getConfigurationSection(ConfigPaths.MODIFIERS_CUSTOM_DESCRIPTIONS)?.getValues(false) ?: emptyMap())
        .mapNotNull { (name, value) -> (value as? String)?.takeIf { it.isNotEmpty() }?.let { name to it } }
        .toMap()

    // Custom map menu item materials. Keys are map names, values are material names.
    // Overrides the default SLIME_BALL (leader) / FIRE_CHARGE (others) in the map menu.
    val mapCustomMaterials: Map<String, String> get() = ((provider as? PaperConfigProvider)?.configuration?.getConfigurationSection(ConfigPaths.MAPS_CUSTOM_MATERIALS)?.getValues(false) ?: emptyMap())
        .mapNotNull { (name, value) -> (value as? String)?.takeIf { it.isNotEmpty() }?.let { name to it } }
        .toMap()

    var itemsBlacklist by custom("items.blacklist", PPEntryTypes.minecraftRegistry(org.bukkit.Registry.MATERIAL).list, listOf(
        Material.AIR,
        Material.BEDROCK,
        Material.COMMAND_BLOCK,
        Material.CHAIN_COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK,
        Material.COMMAND_BLOCK_MINECART,
        Material.BARRIER,
        Material.STRUCTURE_BLOCK,
        Material.STRUCTURE_VOID,
        Material.LIGHT,
        Material.DEBUG_STICK,
        Material.SPAWNER,
        Material.JIGSAW,
        Material.DRAGON_EGG,
        Material.ENDER_DRAGON_SPAWN_EGG,
        // Horse armor is useless in a pillar fight and the warden egg is pure grief; peaceful mob
        // eggs are filler, so they never drop. Boss eggs (wither, elder guardian) are banned too:
        // a wither or elder guardian would end every game on the spot, in every mode.
        Material.WITHER_SPAWN_EGG,
        Material.ELDER_GUARDIAN_SPAWN_EGG,
        Material.WARDEN_SPAWN_EGG,
        Material.LEATHER_HORSE_ARMOR,
        Material.IRON_HORSE_ARMOR,
        Material.GOLDEN_HORSE_ARMOR,
        Material.DIAMOND_HORSE_ARMOR,
        Material.ALLAY_SPAWN_EGG,
        Material.AXOLOTL_SPAWN_EGG,
        Material.BAT_SPAWN_EGG,
        Material.BEE_SPAWN_EGG,
        Material.CAT_SPAWN_EGG,
        Material.CHICKEN_SPAWN_EGG,
        Material.COD_SPAWN_EGG,
        Material.COW_SPAWN_EGG,
        Material.DOLPHIN_SPAWN_EGG,
        Material.DONKEY_SPAWN_EGG,
        Material.FOX_SPAWN_EGG,
        Material.FROG_SPAWN_EGG,
        Material.GLOW_SQUID_SPAWN_EGG,
        Material.GOAT_SPAWN_EGG,
        Material.HORSE_SPAWN_EGG,
        Material.LLAMA_SPAWN_EGG,
        Material.MOOSHROOM_SPAWN_EGG,
        Material.MULE_SPAWN_EGG,
        Material.OCELOT_SPAWN_EGG,
        Material.PANDA_SPAWN_EGG,
        Material.PARROT_SPAWN_EGG,
        Material.PIG_SPAWN_EGG,
        Material.POLAR_BEAR_SPAWN_EGG,
        Material.RABBIT_SPAWN_EGG,
        Material.SALMON_SPAWN_EGG,
        Material.SHEEP_SPAWN_EGG,
        Material.SKELETON_HORSE_SPAWN_EGG,
        Material.SNIFFER_SPAWN_EGG,
        Material.SQUID_SPAWN_EGG,
        Material.STRIDER_SPAWN_EGG,
        Material.TADPOLE_SPAWN_EGG,
        Material.TRADER_LLAMA_SPAWN_EGG,
        Material.TROPICAL_FISH_SPAWN_EGG,
        Material.TURTLE_SPAWN_EGG,
        Material.VILLAGER_SPAWN_EGG,
        Material.WANDERING_TRADER_SPAWN_EGG,
        Material.WOLF_SPAWN_EGG,
        Material.ZOMBIE_HORSE_SPAWN_EGG,
    ))

    var disableFastStats by boolean("disable-faststats", false)

    var timeAfterGame by int("time-after-game", 10)
    var winnerCelebrationSeconds by int("winner-celebration-seconds", 10)
    var killCreditWindow by int("kill-credit-window", 15)
    var itemCleanupInterval by int("items.cleanup-interval", 120)
    var avoidHeldSlot by boolean("items.avoid-held-slot", true)
    var powerUpsEnabled by boolean("items.power-ups", true)
    var powerUpChance by int("items.power-up-chance", 10) // Percent chance a drop is a power-up instead.
    var specialChance by int("items.special-chance", 3) // Percent chance a drop is a special item on its own.
    var enchantChance by int("items.enchant-chance", 10) // Percent chance gear drops pre-enchanted.

    var borderRadius by int("border.radius", 70)
    var borderBottomOffset by int("border.bottom-offset", 40)
    var borderTopOffset by int("border.top-offset", 60)

    // Whether the arena is re-pasted on reset (rebuilds the platforms/blocks rather than reusing
    // the previous game's world state). On by default; disabling keeps the prior layout.
    var arenaResetRePaste by boolean("arena.reset-repaste", true)

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

    // The void death height. Players below this y-coordinate die.
    var deathHeight by int("death-height", 0)

    // The weighted item pool: material name -> weight. A higher weight means a higher chance of getting the item.
    // If the pool is empty, every non-blacklisted material is given an equal chance instead.
    val itemsPool: Map<String, Int> get() = ((provider as? PaperConfigProvider)?.configuration?.getConfigurationSection(ConfigPaths.ITEMS_POOL)?.getValues(false) ?: emptyMap())
        .mapNotNull { (name, value) -> (value as? Int)?.takeIf { it > 0 }?.let { name to it } }
        .toMap()

    fun getSpawnLocation(fallbackWorld: World): Location {
        val world = spawnWorld.value ?: fallbackWorld
        return if (spawnCord.y == -64.0) world.spawnLocation else spawnCord.toLocation(world)
    }

    fun getLobbySpawn(): Location {
        // The lobby is the world configured in 'player-spawn.world': the same world players spawn
        // in on join, never a plugin/game world.
        val world = spawnWorld.value ?: Bukkit.getWorlds().firstOrNull()
            ?: return Location(null, 0.0, 64.0, 0.0)
        return world.spawnLocation
    }

    fun queueLocation(world: World) = if (queueCord.y == -64.0) world.spawnLocation else queueCord.toLocation(world)

    fun init() {
        val result = loadChecking()

        when (result) {
            ConfigLoadResult.LOADED -> FortunePillars.LOG.info("Configuration loaded.")
            ConfigLoadResult.CREATED -> FortunePillars.LOG.info("Configuration has been created.")
            ConfigLoadResult.UPDATED -> {
                FortunePillars.LOG.warn("============================= ! NOTE ! =========================")
                FortunePillars.LOG.warn("| The config has been updated and may need to be reconfigured. |")
                FortunePillars.LOG.warn("|    The old config has been backed up as 'config.yml.old'.    |")
                FortunePillars.LOG.warn("================================================================")
            }
            ConfigLoadResult.UPDATED_AND_MIGRATED -> {
                FortunePillars.LOG.info("============================= ! NOTE ! =======================")
                FortunePillars.LOG.info("| The config has been updated and was successfully migrated. |")
                FortunePillars.LOG.info("|   The old config has been backed up as 'config.yml.old'.   |")
                FortunePillars.LOG.info("==============================================================")
            }
        }

        // NOTE: never save() here. YamlConfiguration.save() rewrites the entire file, which
        // destroys comments/formatting and, worse, reverts any edits made to config.yml while the
        // server is running (the in-memory copy would be stale). The file is only ever written by
        // the explicit /pp-config modify command.
    }

    fun loadChecking(): ConfigLoadResult = load()

    // Whether edits to config.yml on disk are picked up automatically while the server runs.
    // Toggled through `config.auto-reload`; read live, so flipping it in the file applies on the
    // next poll.
    var autoReload by boolean("config.auto-reload", true)

    private var lastConfigModified = 0L

    // Polls the config file for external edits and reloads it when it changed, so admins can edit
    // config.yml in place without restarting (or without even running /pp-config reload).
    fun checkAutoReload() {
        if (!autoReload) return
        // Never reload mid-game: a reload can change deathHeight/borderRadius under running players,
        // which would teleport or kill them unpredictably. The poll timer keeps running; changes
        // made to config.yml while a game is active are picked up on the next poll after it ends.
        if (GameManager.games.isNotEmpty()) return
        val file = provider.path.toFile()
        if (!file.exists()) return
        val modified = file.lastModified()
        if (lastConfigModified == 0L) {
            lastConfigModified = modified
            return
        }
        if (modified != lastConfigModified) {
            lastConfigModified = modified
            // The file may be mid-edit or malformed (half-written YAML, wrong types). A reload that
            // throws here would take the whole ticking task down with it, so a failed reload keeps
            // the last good config instead. The error is logged once per change; a later, valid
            // edit will still reload fine.
            try {
                load()
                validateValues()
                FortunePillars.LOG.info("Configuration reloaded from file.")
            } catch (e: Exception) {
                FortunePillars.LOG.warn("Failed to reload configuration from file; keeping the previous config. Cause: ${e.message}")
            }
        }
    }

    // Cross-checks that reloaded values are still usable. Individual bad entries are replaced with
    // a safe default instead of corrupting the running game, and the affected value is reported.
    private fun validateValues() {
        if (platformHeight <= 0.0) platformHeight = 200.0
        if (platformDistanceFactor <= 0.0) platformDistanceFactor = 10.0
        if (borderRadius < 5) borderRadius = 70
        if (borderBottomOffset < 0) borderBottomOffset = 40
        if (borderTopOffset < 0) borderTopOffset = 60
        if (queueMinPlayers < 2) queueMinPlayers = 2
        if (queueMaxPlayers < queueMinPlayers) queueMaxPlayers = queueMinPlayers
        if (queueStartDelay < 0) queueStartDelay = 60
        if (queueStartDelayHalf < 0) queueStartDelayHalf = 30
        if (queueStartDelayFull < 0) queueStartDelayFull = 5
        if (queueDefaultTime < 1) queueDefaultTime = 10
        if (itemCleanupInterval < 1) itemCleanupInterval = 120
        if (powerUpChance < 0) powerUpChance = 10
        if (specialChance < 0) specialChance = 3
        if (enchantChance < 0) enchantChance = 10
        if (killCreditWindow < 1) killCreditWindow = 15
        // Scoreboard refresh rates are in ticks: below 1 would spin the scheduler, above 200
        // makes the displays feel frozen.
        if (scoreboardUpdateInterval < 1) scoreboardUpdateInterval = 5
        if (scoreboardUpdateInterval > 200) scoreboardUpdateInterval = 200
        if (queueScoreboardUpdateInterval < 1) queueScoreboardUpdateInterval = 20
        if (queueScoreboardUpdateInterval > 200) queueScoreboardUpdateInterval = 200
        // Ping display: unknown style -> "ms"; thresholds must stay ordered or the colors lie.
        if (scoreboardPingStyle !in setOf("ms", "plain", "colored")) scoreboardPingStyle = "ms"
        if (scoreboardPingGoodMs < 1) scoreboardPingGoodMs = 60
        if (scoreboardPingWarnMs <= scoreboardPingGoodMs) scoreboardPingWarnMs = scoreboardPingGoodMs * 2
    }

    // Re-anchors the auto-reload watcher to the file's current state, so a manual reload (or an
    // in-game /pp-config modify write) is never mistaken for an external edit.
    fun resetAutoReloadBaseline() {
        val file = provider.path.toFile()
        if (file.exists()) lastConfigModified = file.lastModified()
    }

    // Returns every material the given mode is barred from dropping, on top of the global
    // `items.blacklist`. Reads `modes.<namespace>.blacklist` live, so it picks up reloads.
    fun modeBlacklist(namespace: String): Set<Material> =
        provider.getStringList(ConfigPaths.modeBlacklist(namespace))
            ?.mapNotNull { Material.matchMaterial(it) }?.toSet()
            ?: emptySet()

    // ===== Rewards & leaderboards (Phase 3 progression) =====
    // Vault payout to each winner, in economy units. 0 disables the payout.
    val rewardWinAmount: Double get() = provider.getDouble(ConfigPaths.REWARDS_WIN_AMOUNT, 0.0)
    // Console commands run for each winner; %player% is replaced with the winner's name. Empty = none.
    val rewardWinCommands: List<String> get() = provider.getStringList(ConfigPaths.REWARDS_WIN_COMMANDS) ?: emptyList()
    // How many entries the /pp top boards show.
    val leaderboardSize: Int get() = provider.getInt(ConfigPaths.LEADERBOARD_SIZE, 10).coerceAtLeast(1)
}

object PPEntryTypes {
    val placeholder = CustomEntryType(
        BaseEntryTypes.string,
        { PlaceholderNameGetter(it) },
        { it.base }
    )

    fun <T> registry(entries: () -> Map<String, T>) = CustomEntryType(
        BaseEntryTypes.string,
        { entries()[it]!! },
        { entries().entries.first { e -> e.value == it }.key }
    )

    fun <T : Keyed> minecraftRegistry(registry: org.bukkit.Registry<T>) = CustomEntryType(
        BaseEntryTypes.string,
        { NamespacedKey.fromString(it)?.let { key -> registry.get(key) } },
        { registry.getKeyOrThrow(it).asMinimalString() }
    )
}

enum class QueueMethod { COMMAND, AUTO }
