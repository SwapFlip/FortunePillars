package com.marcpg.pillarperil.util

import com.marcpg.libpg.config.*
import com.marcpg.libpg.storing.Cord
import com.marcpg.libpg.util.BasicOptional
import com.marcpg.libpg.util.toLocation
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.Registry
import com.marcpg.pillarperil.game.mode.NormalGame
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
    var maxFall by double("max-fall", 25.0)
    var platformDistanceFactor by double("platform-distance-factor", 10.0)
    var enableDraws by boolean("enable-draws")
    var endingCommands by custom("ending-commands", PPEntryTypes.placeholder.list, listOf())
    var respawnAtConfig by boolean("respawn-at-config")

    var spawnGameMode by enum<GameMode>("player-spawn.game-mode", GameMode.ADVENTURE)
    var spawnWorld by custom("player-spawn.world", PaperEntryTypes.world, BasicOptional.ofNull())
    var spawnCord by custom("player-spawn.location", ExtendedEntryTypes.cordMap, Cord(0.0, -64.0, 0.0))

    var queueEnabled by boolean("queue.enabled")
    var queueMinPlayers by int("queue.min-players", 2)
    var queueMaxPlayers by int("queue.max-players", 8)
    var queueCheckIntervalSecs by int("queue.check-interval", 5)
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

    var scoreboardTitle by string("scoreboard.title", "<bold><gradient:#71CCF8:#FC91EC:#F87171>Pillar Peril")
    var scoreboardLines by custom("scoreboard.lines", BaseEntryTypes.string.list, emptyList())

    var soundEffectsEnabled by boolean("sound-effects.enabled", true)
    var soundEffectsCooldown by int("sound-effects.cooldown", 3)
    var soundEffectsItem by boolean("sound-effects.item", true)

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
    ))

    var disableFastStats by boolean("disable-faststats", false)

    var timeAfterGame by int("time-after-game", 10)
    var killCreditWindow by int("kill-credit-window", 15)
    var itemCleanupInterval by int("items.cleanup-interval", 120)
    var avoidHeldSlot by boolean("items.avoid-held-slot", true)

    // The void death height. Players below this y-coordinate die.
    val deathHeight get() = 0.0

    // The weighted item pool: material name -> weight. A higher weight means a higher chance of getting the item.
    // If the pool is empty, every non-blacklisted material is given an equal chance instead.
    val itemsPool: Map<String, Int> get() = ((provider as? PaperConfigProvider)?.configuration?.getConfigurationSection("items.pool")?.getValues(false) ?: emptyMap())
        .mapNotNull { (name, value) -> (value as? Int)?.takeIf { it > 0 }?.let { name to it } }
        .toMap()

    fun getSpawnLocation(fallbackWorld: World): Location {
        val world = spawnWorld.value ?: fallbackWorld
        return if (spawnCord.y == -64.0) world.spawnLocation else spawnCord.toLocation(world)
    }

    val queueCheckInterval get() = queueCheckIntervalSecs * 20
    fun queueLocation(world: World) = if (queueCord.y == -64.0) world.spawnLocation else queueCord.toLocation(world)

    fun init() {
        val result = loadChecking()

        result.second.forEach { PillarPeril.LOG.error(it) }

        when (result.first) {
            ConfigLoadResult.LOADED -> PillarPeril.LOG.info("Configuration loaded.")
            ConfigLoadResult.CREATED -> PillarPeril.LOG.info("Configuration has been created.")
            ConfigLoadResult.UPDATED -> {
                PillarPeril.LOG.warn("============================= ! NOTE ! =========================")
                PillarPeril.LOG.warn("| The config has been updated and may need to be reconfigured. |")
                PillarPeril.LOG.warn("|    The old config has been backed up as 'config.yml.old'.    |")
                PillarPeril.LOG.warn("================================================================")
            }
            ConfigLoadResult.UPDATED_AND_MIGRATED -> {
                PillarPeril.LOG.info("============================= ! NOTE ! =======================")
                PillarPeril.LOG.info("| The config has been updated and was successfully migrated. |")
                PillarPeril.LOG.info("|   The old config has been backed up as 'config.yml.old'.   |")
                PillarPeril.LOG.info("==============================================================")
            }
        }

        save()
    }

    fun loadChecking(): Pair<ConfigLoadResult, List<String>> {
        val result = load() to mutableListOf<String>()

        if (queueCheckIntervalSecs < 1 && queueCheckIntervalSecs != -1)
            result.second += "Invalid value $queueCheckIntervalSecs for configuration key 'queue.check-interval'."

        return result
    }
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
