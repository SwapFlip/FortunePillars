package com.swapflip.fortunepillars.game

import com.marcpg.libpg.data.time.Time
import com.marcpg.libpg.display.*
import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.Randomizer
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.miniMessage
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.Registry
import com.swapflip.fortunepillars.event.QueueEvents
import com.swapflip.fortunepillars.game.util.Cage
import com.swapflip.fortunepillars.game.util.GameInfo
import com.swapflip.fortunepillars.game.util.GameManager
import com.swapflip.fortunepillars.game.util.QueueManager
import com.swapflip.fortunepillars.generation.Buildings
import com.swapflip.fortunepillars.map.ArenaMap
import com.swapflip.fortunepillars.map.BlockPos
import com.swapflip.fortunepillars.map.MapBounds
import com.swapflip.fortunepillars.map.MapManager
import com.swapflip.fortunepillars.map.MapPaster
import com.swapflip.fortunepillars.map.SchematicReader
import com.swapflip.fortunepillars.player.PillarPlayer
import com.swapflip.fortunepillars.util.*
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

import net.kyori.adventure.title.Title
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.*
import org.bukkit.entity.Firework
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class GamePhase { WAITING, COUNTDOWN, RUNNING, CELEBRATING, ENDED }

abstract class Game(
    val id: String,
    center: Location,
    protected val bukkitPlayers: List<Player>,
    modifiers: List<GameModifier>,
): Ticking {
    var modifiers: List<GameModifier> = modifiers
        set(value) {
            field = value
            modifiersByClass = value.associateBy { it.javaClass }
        }

    // Modifiers never change after construction: typed lookups on hot paths (e.g. the betrayal
    // PvP check on every damage event) resolve in O(1) instead of scanning the list per event.
    // The index is rebuilt only when the `modifiers` list itself is swapped (multi
    // selection does that at activation), so it can never go stale but also never rebuilds per read.
    private var modifiersByClass: Map<Class<*>, GameModifier> = modifiers.associateBy { it.javaClass }

    fun <T : GameModifier> modifierOf(type: Class<T>): T? = modifiersByClass[type] as? T
    enum class EndingCause {
        FORCE,
        TIME_OVER,
        LAST_STANDING,
        DRAW,
        ERROR,
    }

    companion object {
        // The feature flag API only exists on newer Paper versions: resolved once per session.
        private val isEnabledMethodReflection: java.lang.reflect.Method? by lazy {
            runCatching {
                World::class.java.getMethod("isEnabled", Class.forName("io.papermc.paper.world.flag.FeatureDependant"))
            }.getOrNull()
        }

        // How many active games are currently using each world. World gamerule/PvP state is only
        // restored when the last game using that world ends, so two games on the same world can
        // never stomp each other's saved state.
        private val worldUsers = mutableMapOf<World, Int>()

        // Materials that are technically items but make no sense in a loot pool and are therefore
        // rejected even if an admin's blacklist is empty. Everything else is fair game, filtered by
        // the global `items.blacklist`, the per-mode `modes.<namespace>.blacklist` and the world's
        // feature flags (see buildItems).
        val UNOBTAINABLE_ITEMS: Set<Material> = setOf(
            Material.BARRIER, Material.LIGHT, Material.STRUCTURE_VOID, Material.STRUCTURE_BLOCK,
            Material.JIGSAW, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.DEBUG_STICK, Material.KNOWLEDGE_BOOK, Material.SPAWNER, Material.END_PORTAL_FRAME,
            Material.BEDROCK, Material.DRAGON_EGG, Material.REINFORCED_DEEPSLATE,
        )

        fun getColor(left: Float): BossBar.Color = when {
            left < 0.4 -> BossBar.Color.RED
            left < 0.5 -> BossBar.Color.YELLOW
            left < 0.8 -> BossBar.Color.GREEN
            else -> BossBar.Color.PINK
        }

        // Decorative and dead-weight materials that add nothing to a pillar fight: armor trim and
        // netherite upgrade smithing templates, horse armor, pottery sherds. Name-based on purpose -
        // new variants added in later Minecraft versions are filtered automatically.
        // Public so the per-mode config generator (ModeConfigGenerator) can reproduce the exact
        // legal-item filter used here when enumerating a mode's allowed/banned items.
        fun isLootJunk(material: Material): Boolean =
            material.name.endsWith("_ARMOR_TRIM_SMITHING_TEMPLATE") ||
                material == Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE ||
                material.name.endsWith("_HORSE_ARMOR") ||
                material.name.endsWith("_POTTERY_SHERD")

        // Eliminations resolve their delayed follow-up (spectator teleport / win check) 19 ticks
        // (~0.95s) after death; the draw window compares death times against the same offset, so
        // both sites must always use the exact same value.
        const val ELIMINATION_DELAY_TICKS = 19

        fun generateId() = Randomizer.generateRandomString(Constants.GAME_ID_LENGTH, Constants.GAME_ID_CHARSET)
    }

    // ================ CONSTRUCTION DATA ================

    abstract val info: GameInfo

    val center: Location = center.clone().apply { y = Configuration.platformHeight + 1.0 }
    val world: World = center.world
    val startingTick: Int = Bukkit.getCurrentTick()

    // Read-only to the outside: only ever modified at startup, so callers can treat it as frozen.
    private val initialPlayersMutable = mutableListOf<PillarPlayer>()
    val initialPlayers: List<PillarPlayer> get() = initialPlayersMutable
    val players = mutableListOf<PillarPlayer>()
    // UUID -> alive player index, so the extremely frequent "is this player in a game?" lookups
    // stay O(1) per game instead of scanning the player list every time.
    private val playersByUuid = mutableMapOf<UUID, PillarPlayer>()

    // Initial target consisting of all `initialPlayers`, just used for caching.
    private val initialTarget: ForwardingMinecraftReceiver by lazy { initialPlayers.toList().receiver() }

    var radius: Double = 0.0

    lateinit var items: List<Material> protected set
    lateinit var buildings: Buildings private set

    // Set before init() when the game runs on a pasted arena map:
    var map: ArenaMap? = null
    var arenaBounds: MapBounds? = null
    var customItemCountdown: (() -> Long)? = null

    // The horizontal anchor the play area and the out-of-bounds radius are centered on: the map's
    // spectator spawn projected onto the pasted arena, or null when playing without a pasted map.
    private fun arenaAnchor(): Pair<Int, Int>? {
        val bounds = arenaBounds
        val map = map
        if (bounds == null || map == null) return null
        val spectator = map.spectatorSpawn ?: return null
        return (bounds.minX + spectator.x - map.origin.x) to (bounds.minZ + spectator.z - map.origin.z)
    }

    // The play area is a square around the spectator-spawn anchor, which is where the actual
    // gameplay happens. Games without a pasted arena fall back to a square around the game's
    // center, so hazards like lava still work. Cached per size/arena: modifiers call this every
    // tick, and the arena bounds only change when the map is (re)pasted, never mid-match.
    private var playAreaCache: Pair<MapBounds?, Map<Int, MapBounds>> = null to emptyMap()

    fun playArea(size: Int): MapBounds {
        val bounds = arenaBounds
        val (cachedBounds, bySize) = playAreaCache
        if (cachedBounds === bounds) {
            bySize[size]?.let { return it }
        } else {
            playAreaCache = bounds to emptyMap()
        }

        val result = if (bounds != null) {
            val (centerX, centerZ) = arenaAnchor() ?: ((bounds.minX + bounds.maxX) / 2 to (bounds.minZ + bounds.maxZ) / 2)
            val half = size / 2
            MapBounds(centerX - half, bounds.minY, centerZ - half, centerX + half, bounds.maxY, centerZ + half)
        } else {
            val half = size / 2
            val cx = center.blockX
            val cz = center.blockZ
            MapBounds(cx - half, center.blockY - half, cz - half, cx + half, center.blockY + half, cz + half)
        }

        playAreaCache = bounds to (playAreaCache.second + (size to result))
        return result
    }

    // ==================== GAME STATE ====================

    val timeLeft = Time()
    val itemCountdown = Time(0, allowNegatives = true)

    private var spawnCagesReleased = false

    // The game has started once the cages release. Damage and inventory locking key off this
    // instead of the item countdown, which keeps cycling for the entire match.
    val started: Boolean get() = spawnCagesReleased

    // The tick the fight actually started (when the cages opened). Modifiers anchor their start
    // delays to this instead of the construction tick, so a long cage/vote phase can never let a
    // hazard fire early (or drift from the fight's real start).
    var fightStartTick: Int = 0
        private set

    // True while the last survivor's victory celebration (fireworks + title) plays out before end().
    private var celebrating = false

    // ================ MULTI MODIFIER SELECTION ================
// "Multi Mode": the cages stay shut after the countdown and every player picks their
// own modifiers in a GUI. Any modifier with at least one vote activates when everyone is done.

    var multiSelect = false
    var multiSelectionDone = false

    // Each player's selected modifiers by their own UUID.
    val multiSelections = mutableMapOf<UUID, MutableSet<String>>()

    private val multiConfirmed = mutableSetOf<UUID>()
    private var multiMenuOpened = false
    private var multiSelectionStartedTick = 0

    // A player who disconnects mid-selection is eliminated and leaves `players`, so requiring
    // every initial player to confirm could deadlock the game forever. Only alive players count,
    // and a hard timeout activates whatever has been picked if anyone is still stuck in the menu.
    private fun multiSelectionReady(): Boolean =
        multiConfirmed.size >= players.size && players.isNotEmpty()

    // The invisible barrier cylinder that spawns around the arena when the cages open, keeping
    // players from running out of bounds. Placed and removed in chunks to stay lag-free.
    var border: GameBorder? = null
        private set

    val deathHeight: Double get() = map?.deathHeight?.toDouble() ?: Configuration.deathHeight.toDouble()

    fun itemCountdown(): Long = customItemCountdown?.invoke() ?: info.itemCountdown()

    fun typeName(locale: Locale): String = modifiers.firstOrNull()?.info?.name(locale) ?: "-"

    // The tick modifiers anchor their start delays to: the fight start once the cages opened,
    // falling back to the construction tick if the fight never started (modifiers only tick after
    // releaseCages(), so in practice this is always fightStartTick).
    fun anchorTick(): Int = if (started) fightStartTick else startingTick

    // Fraction of the item cycle left. The old (total - 1) divisor turned a one-second cycle into
    // a division by zero (NaN progress), so the fraction is plain left/total with a zero guard.
    val itemCountdownPercentage: Float
        get() {
            val total = itemCountdown()
            if (total <= 0L) return 1.0f
            return (itemCountdown.get().toFloat() / total).coerceIn(0.0f, 1.0f)
        }

    private val tickEvents = mutableMapOf<() -> Unit, Int>()
    private val itemEvents = mutableListOf<() -> Unit>()

    var ending = false

    // The current high-level phase of the game, derived from the underlying state flags.
    val phase: GamePhase
        get() = when {
            ending -> GamePhase.ENDED
            celebrating -> GamePhase.CELEBRATING
            started -> GamePhase.RUNNING
            else -> GamePhase.COUNTDOWN
        }

    // Centralized listener registry so cleanup() can unregister every event listener this game
    // registered, instead of leaking handlers that fire into a dead game.
    private val registeredListeners = mutableSetOf<org.bukkit.event.Listener>()

    fun registerListener(l: org.bukkit.event.Listener) {
        org.bukkit.Bukkit.getPluginManager().registerEvents(l, com.swapflip.fortunepillars.FortunePillars.PLUGIN)
        registeredListeners += l
    }

    // Centralized scheduled-task registry so cleanup() can cancel every task this game scheduled,
    // instead of leaving delayed callbacks firing into a dead game.
    private val scheduledTasks = mutableSetOf<org.bukkit.scheduler.BukkitTask>()

    fun runLater(ticks: Long, block: () -> Unit): org.bukkit.scheduler.BukkitTask =
        org.bukkit.Bukkit.getScheduler().runTaskLater(com.swapflip.fortunepillars.FortunePillars.PLUGIN, block, ticks)
            .also { scheduledTasks += it }

    // ================= DISPLAY METHODS =================

    // Placeholders usable in `scoreboard.lines`. Parsed against this exact set, so real
    // MiniMessage tags (<gray>, <bold>, ...) in config lines are never mistaken for placeholders.
    val scoreboardPlaceholders: Set<String> = setOf(
        "mode", "type", "map", "player", "time", "kills", "alive", "countdown",
        "health", "ping", "eliminated", "online", "id", "leader",
        "started", "world", "coords", "facing", "clock", "border", "death-height",
    )

    open val scoreboard: ((PillarPlayer) -> SimpleScoreboard)? = { p -> createScoreboard(p) }

    private fun createScoreboard(p: PillarPlayer): SimpleScoreboard {
        val title = MINI_MESSAGE.deserialize(Configuration.scoreboardTitle)
        val interval = Configuration.scoreboardUpdateInterval.toLong()
        val showNumbers = Configuration.scoreboardShowNumbers

        if (Configuration.scoreboardLines.isNotEmpty()) {
            // The line skeleton (everything around the <placeholders>) is parsed once per scoreboard:
            // per-update work is only substituting the value text between pre-parsed segments, which
            // also stops a player name from ever being interpreted as MiniMessage markup.
            val entries = Configuration.scoreboardLines.map { line ->
                val template = ScoreboardTemplates.parse(line, scoreboardPlaceholders)
                ScoreboardTemplates.TemplateEntry(template, showNumbers) { locale, key -> resolveScoreboardValue(key, p, locale) }
            }.toTypedArray()
            return SimpleScoreboard(p, interval, title, *entries)
        }

        return SimpleScoreboard(p, interval, title,
            StaticValueScoreboardEntry(p.locale().component("scoreboard.mode").style(info.keyStyle()), component(info.name(p.locale())).style(info.valueStyle)),
            StaticValueScoreboardEntry(p.locale().component("scoreboard.type").style(info.keyStyle()), component(typeName(p.locale())).style(info.valueStyle)),
            StaticValueScoreboardEntry(p.locale().component("scoreboard.map").style(info.keyStyle()), component(map?.name ?: "-").style(info.valueStyle)),
            StaticValueScoreboardEntry(p.locale().component("scoreboard.name").style(info.keyStyle()), component(p.name()).style(info.valueStyle)),
            ValueScoreboardEntry(p.locale().component("scoreboard.time").style(info.keyStyle())) { component(timeLeft.oneUnitFormatted).style(info.valueStyle) },
            ValueScoreboardEntry(p.locale().component("scoreboard.kills").style(info.keyStyle())) { component(p.kills.toString()).style(info.valueStyle) },
            ValueScoreboardEntry(p.locale().component("scoreboard.alive").style(info.keyStyle())) { component(players.size.toString()).style(info.valueStyle) },
            ValueScoreboardEntry(p.locale().component("scoreboard.health").style(info.keyStyle())) { component(formatHearts(p.player.health)).style(info.valueStyle) },
        )
    }

    // The "leader" placeholder is recomputed for every scoreboard line each tick: cache it once
    // per tick so only the first line's resolution does the scan, the rest reuse the result.
    private var cachedLeaderTick: Int = -1
    private var cachedLeader: PillarPlayer? = null

    // Resolves one <placeholder> of the in-game scoreboard on every update. Player-controlled
    // text (names) is escaped so it can never be interpreted as MiniMessage markup.
    private fun resolveScoreboardValue(key: String, p: PillarPlayer, locale: Locale): String = when (key) {
        "mode" -> info.name(locale)
        "type" -> typeName(locale)
        "map" -> map?.name ?: "-"
        "player" -> p.name().escapeTags()
        "time" -> timeLeft.oneUnitFormatted
        "kills" -> p.kills.toString()
        "alive" -> players.size.toString()
        "countdown" -> itemCountdown.oneUnitFormatted
        "health" -> formatHearts(p.player.health)
        "ping" -> formatPing(p.player.ping)
        "eliminated" -> (initialPlayers.size - players.size).toString()
        "online" -> Bukkit.getOnlinePlayers().size.toString()
        "id" -> id
        "leader" -> {
            val tick = Bukkit.getCurrentTick()
            if (tick != cachedLeaderTick) {
                cachedLeaderTick = tick
                cachedLeader = initialPlayers.filter { it.kills > 0 }.maxByOrNull { it.kills }
            }
            cachedLeader?.let { "${it.name().escapeTags()} (${it.kills})" } ?: "-"
        }
        "started" -> initialPlayers.size.toString()
        "world" -> world.name
        "coords" -> "${p.player.location.blockX}, ${p.player.location.blockY}, ${p.player.location.blockZ}"
        "facing" -> ScoreboardTemplates.facing(p.player.location.yaw)
        "clock" -> ScoreboardTemplates.clock()
        "border" -> Configuration.borderRadius.toString()
        "death-height" -> deathHeight.toInt().toString()
        else -> ""
    }

    // <ping> rendering, styled by `scoreboard.ping-style`: plain number, with an "ms" suffix, or
    // colored by quality (green up to good-ms, yellow up to warn-ms, red beyond).
    private fun formatPing(ping: Int): String = when (Configuration.scoreboardPingStyle) {
        "plain" -> ping.toString()
        "colored" -> when {
            ping <= Configuration.scoreboardPingGoodMs -> "<green>$ping ms"
            ping <= Configuration.scoreboardPingWarnMs -> "<yellow>$ping ms"
            else -> "<red>$ping ms"
        }
        else -> "${ping}ms"
    }

    // Formats a health value (in half-hearts) as hearts: 20.0 -> "10", 13.0 -> "6.5".
    // Locale.US keeps the decimal separator a dot regardless of the server's system language.
    private fun formatHearts(health: Double): String {
        val hearts = health / 2.0
        return if (hearts == hearts.toInt().toDouble()) hearts.toInt().toString() else "%.1f".format(Locale.US, hearts)
    }

    // The bossbar text only changes when the remaining seconds change: rebuilding the component on
    // every refresh is wasted work, so the last rendered value is cached.
    private var cachedBossbarSecond = Long.MIN_VALUE
    private var cachedBossbarComponent: Component? = null

    open val bossBarCreator: () -> SimpleBossBar = { SimpleBossBar(target(false),
        20,
        { locale ->
            val left = itemCountdown.get()
            if (left != cachedBossbarSecond) {
                cachedBossbarSecond = left
                cachedBossbarComponent = if (left <= 0L)
                    locale.component("bossbar.ready", color = NamedTextColor.GREEN)
                else
                    locale.component("bossbar.time", itemCountdown.oneUnitFormatted, color = TextColor.color(0x3399FF))
            }
            cachedBossbarComponent!!
        },
        { itemCountdownPercentage },
        { getColor(itemCountdownPercentage) },
        { BossBar.Overlay.NOTCHED_10 }
    ) }

    var bossBar: SimpleBossBar? = null
        private set

    // =============== OVERRIDABLE METHODS ===============

    // The game permanently changes world settings (gamerules, PvP) on shared, admin-built worlds:
    // the originals are captured at init and restored by cleanup(), so the world is left exactly
    // as the admin configured it (and concurrent games can't stomp each other's state).
    private var savedImmediateRespawn = true
    private var savedMobSpawning = true
    private var savedNaturalRegen = true
    private var savedPvP = true
    private var worldStateSaved = false

    // Set when eliminate() schedules the last-standing/draw resolution, so the per-tick empty-roster
    // check does not force-end the game (and skip that resolution) the instant the final player is
    // eliminated. Cleared once the delayed block runs.
    private var winResolutionPending = false

    open fun init() {
        // A match must always start on a clean slate: wipe every non-player entity in the dedicated
        // queue world (leftover mobs from a game that ended uncleanly, stray arrows, dropped items),
        // so nothing from an earlier round spawns into this one. Shared admin worlds are skipped.
        if (world.name == Cage.queueWorldName)
            world.getEntities().forEach { if (it !is Player) it.remove() }

        savedImmediateRespawn = world.getGameRuleValue(GameRule.DO_IMMEDIATE_RESPAWN) ?: true
        savedMobSpawning = world.getGameRuleValue(GameRule.DO_MOB_SPAWNING) ?: true
        savedNaturalRegen = world.getGameRuleValue(GameRule.NATURAL_REGENERATION) ?: true
        savedPvP = world.getPVP()
        worldStateSaved = true
        // Count this game as a user of the world so its state is only restored once the last
        // game using it ends (concurrent games would otherwise clobber each other's settings).
        worldUsers[world] = (worldUsers[world] ?: 0) + 1

        world.setGameRuleSafe("DO_IMMEDIATE_RESPAWN", "IMMEDIATE_RESPAWN", true)
        world.setGameRuleSafe("DO_MOB_SPAWNING", "SPAWN_MOBS", true)
        world.setGameRuleSafe("NATURAL_REGENERATION", "NATURAL_HEALTH_REGENERATION", true)
        world.setPVP(true)

        bukkitPlayers
            .map { PillarPlayer(it, this, QueueManager.consumeJoinSnapshot(it)) }
            .onEach {
                QueueManager.remove(it.player)

                // Close any open inventory first, so an item held on the cursor drops into the
                // inventory and gets wiped with it instead of being smuggled into the game.
                it.player.closeInventory()
                it.player.openInventory.setCursor(null)

                it.player.gameMode = GameMode.SURVIVAL
                it.player.clearActivePotionEffects()
                it.player.inventory.clear()
                refill(it.player)
            }
            .forEach {
                initialPlayersMutable += it
                players += it
                playersByUuid[it.uuid()] = it
            }

        // Resolved once per server session instead of on every game init.
        val isEnabledMethod = isEnabledMethodReflection

        // The feature-flag filter must never abort the game start: on servers where the check is
        // unavailable, or for materials without an item mapping (legacy aliases like FLOWING_WATER
        // throw inside Purpur's isEnabledByFeature), the material is allowed - the isItem() and
        // blacklist filters already prune everything that makes no sense on the running version.
        val enabledCheck: (Material) -> Boolean = { material ->
            runCatching {
                if (isEnabledMethod != null)
                    isEnabledMethod.invoke(world, material) as Boolean
                else
                    @Suppress("DEPRECATION")
                    material.isEnabledByFeature(world)
            }.getOrDefault(true)
        }

        items = buildItems(enabledCheck)

        radius = initialPlayers.size * Configuration.platformDistanceFactor / Math.TAU

        modifiers.forEach { it.init() }

        buildings = Buildings(this, info.horGen().constructGen(this), info.vertGen().constructGen(this))

        val map = this.map
        when {
            map != null && arenaBounds != null -> {
                info("Playing on pre-pasted arena map \"${map.name}\".")
                cagePlayersOnMap(map)
            }
            map != null -> startOnMap(map)
            else -> buildAndTeleport()
        }

        modifiers.forEach { it.customBuild() }

        bossBar = bossBarCreator().also { it.start() }

        if (Configuration.itemCleanupInterval > 0)
            addTickEvent(Configuration.itemCleanupInterval.toLong() * 20L) { removeDroppedItems() }

        timeLeft.set(info.timeLimit())
        itemCountdown.set(itemCountdown())

        GameManager.add(this)
        info("Initialized the game.")
    }

    protected open fun buildItems(enabledCheck: (Material) -> Boolean): List<Material> {
        // Weighted generation over ALL legal items: the mode's loot weighting function decides how
        // likely each material is (0 = banned for that mode), and the effective blacklist (global
        // items.blacklist + per-mode blacklist + unobtainable technical blocks) vetoes on top.
        // Unobtainable creative-only blocks (barrier, command blocks, spawner, ...) never enter the pool.
        val weights = info.lootWeights()
        val blacklist = ModeConfigs.effectiveBlacklist(info.namespace)
        return Material.values()
            .asSequence()
            .filter { it.isItem && it !in blacklist && !isLootJunk(it) && enabledCheck(it) }
            .flatMap { material ->
                val weight = weights(material)
                if (weight <= 0) emptySequence() else sequence { repeat(weight) { yield(material) } }
            }
            .toList()
    }

    private fun startOnMap(map: ArenaMap) {
        val file = MapManager.schematicFile(map.schematic)
        val schematic = file?.let { runCatching { SchematicReader.read(it) }.getOrNull() }

        if (schematic == null) {
            error("Could not read schematic \"${map.schematic}\" for map \"${map.name}\", falling back to default generation.", IllegalStateException("Missing or invalid schematic file."))
            buildAndTeleport()
            return
        }

        // Wipe the target volume (and any entities in it) before pasting, so a manual start on a
        // world with leftover blocks/mobs doesn't paste the arena on top of old terrain. Mirrors the
        // queue path's pasteMapUnchecked clearing.
        val minX = map.origin.x
        val minY = map.origin.y
        val minZ = map.origin.z
        val maxX = map.origin.x + schematic.width
        val maxY = map.origin.y + schematic.height
        val maxZ = map.origin.z + schematic.length
        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val block = world.getBlockAt(x, y, z)
            if (block.type != Material.AIR) block.setType(Material.AIR, false)
        }
        world.getEntities().forEach { entity ->
            if (entity is Player) return@forEach
            val loc = entity.location
            if (loc.blockX in minX..maxX && loc.blockY in minY..maxY && loc.blockZ in minZ..maxZ)
                entity.remove()
        }

        arenaBounds = MapPaster.paste(schematic, world, map.origin)
        cagePlayersOnMap(map)
    }

    // Teleports players into cages at the map's spawns. Used both when pasting a fresh arena
    // (startOnMap) and when the queue already pasted one (map != null && arenaBounds != null), so
    // the pre-game containment is identical on both paths.
    private fun cagePlayersOnMap(map: ArenaMap) {
        // Spawns are unique-ified here so two players can never end up in the same cage: maps may
        // contain duplicate entries, or (0,0,0) placeholders left behind when spawn N was set before
        // spawns 1..N-1. If the map runs out of usable spawns, players are offset from the origin.
        val usableSpawns = map.spawns.distinct().filter { it != BlockPos(0, 0, 0) }
        val used = mutableSetOf<BlockPos>()
        players.forEachIndexed { i, p ->
            val spawn = usableSpawns.getOrNull(i)?.takeUnless { it in used }
                ?: usableSpawns.firstOrNull { it !in used }
                ?: BlockPos(map.origin.x, map.origin.y + i * 3, map.origin.z)
            used += spawn
            val feet = spawn.toLocation(world, yOffset = 3.0)
            Cage.gameCage(p.player, feet)
            p.player.teleport(feet)
        }
    }

    private fun buildAndTeleport() {
        val centeredCenter = center.toCenterLocation()
        buildings.generate().forEachIndexed { i, l ->
            val location = l.clone().add(0.0, 1.0, 0.0).toCenterLocation()
            location.yaw = Math.toDegrees(atan2(-(centeredCenter.x - location.x), centeredCenter.z - location.z)).toFloat()
            players[i].teleport(location)
        }
    }

    open fun addItem(player: PillarPlayer) = player.giveItems(items, info.dropCount())

    // Restores a player to full health, hunger and saturation. Called both when the game initializes
    // and again right as the cages release, so nobody starts the actual fight with drained stats.
    protected fun refill(player: Player) {
        player.foodLevel = 20
        player.saturation = 20.0f

        val maxHealth = player.getAttributeSafe("MAX_HEALTH")?.value
        if (maxHealth != null) {
            player.health = maxHealth
        } else {
            player.health = player.maxHealth
        }
    }

    // ================= UTILITY METHODS =================

    fun info(msg: String) = FortunePillars.LOG.info("[${Constants.GAME_LOG_PREFIX}$id] $msg")
    fun warn(msg: String) = FortunePillars.LOG.warn("[${Constants.GAME_LOG_PREFIX}$id] $msg")
    fun error(msg: String, e: Throwable) = FortunePillars.LOG.error("[${Constants.GAME_LOG_PREFIX}$id] $msg", e)

    protected fun addTickEvent(interval: Time, event: () -> Unit) = addTickEvent(interval.get() * 20L, event)

    protected fun addTickEvent(intervalTicks: Long, event: () -> Unit) {
        tickEvents[event] = intervalTicks.toInt()
    }

    protected fun addItemEvent(event: () -> Unit) {
        itemEvents.add(event)
    }

    fun target(onlyAlive: Boolean = true): MinecraftReceiver = if (onlyAlive) players.receiver() else initialTarget

    fun player(bukkitPlayer: Player, onlyAlive: Boolean = true): PillarPlayer? =
        player(bukkitPlayer.uniqueId, onlyAlive)

    fun player(uuid: UUID, onlyAlive: Boolean = true): PillarPlayer? {
        if (onlyAlive)
            return playersByUuid[uuid]
        for (player in initialPlayers) {
            if (player.uuid() == uuid)
                return player
        }
        return null
    }

    // Whether a location lies within this game's arena: the pasted map bounds when a map is in
    // use, otherwise the generated buildings' radius around the center.
    fun isWithin(location: Location): Boolean {
        val bounds = arenaBounds
        if (bounds != null)
            return location.blockX in bounds.minX..bounds.maxX &&
                location.blockY in bounds.minY..bounds.maxY &&
                location.blockZ in bounds.minZ..bounds.maxZ
        return center.distanceSquared(location) < (buildings.placedRadius * 2) * (buildings.placedRadius * 2)
    }

    // ================ GAME-LOGIC METHODS ================

    // The spot eliminated players watch from and the celebrating winner is rescued to. Always
    // clamped above the death height: a badly-saved spectator spawn below it would re-trigger the
    // void handling on every tick (rescue teleports, deaths, respawns) in an endless loop.
    fun safeSpectatorSpot(): Location {
        val spot = map?.spectatorLocation(world) ?: center
        if (spot.y >= deathHeight) return spot
        val safe = spot.clone()
        safe.y = deathHeight + 3
        return safe
    }

    fun eliminate(player: PillarPlayer) {
        if (ending || player !in players) return

        players -= player
        playersByUuid.remove(player.uuid())
        info("$player got eliminated.")

        // Count the elimination as a death in the player's progression (the win/loss bookkeeping
        // happens once, at game end).
        PlayerStats.addDeath(player.uuid())

        player.deathTime = Bukkit.getCurrentTick()

        modifiers.forEach { it.onPlayerDeath(player) }
        // Runs unconditionally at elimination time (not inside the delayed block below, which
        // skips offline players): a modifier hooking this must see every elimination, even when
        // the player left the server the same instant they died.
        modifiers.forEach { it.onPostPlayerDeath(player) }

        val win = players.size <= 1
        // When everyone is dead, the last one to die is still the last one standing - never end a
        // LAST_STANDING game with an empty winner list.
        val winners = players.toList().ifEmpty { listOf(player) }
        // Flag the pending resolution so the per-tick empty-roster force-end does not preempt it.
        if (win) winResolutionPending = true

        bukkitRunLater(ELIMINATION_DELAY_TICKS.toLong()) { // 0.95s / 950ms
            // The win/draw resolution is now handled (or the game already ended by another cause),
            // so the per-tick empty-roster guard may force-end again.
            winResolutionPending = false
            if (!ending && !celebrating && win) {
                val lastDeath = initialPlayers.mapNotNull { it.deathTime }.maxOrNull() ?: return@bukkitRunLater
                val drawWinners = initialPlayers.filter { (it.deathTime ?: Int.MIN_VALUE) + ELIMINATION_DELAY_TICKS >= lastDeath }

                if (Configuration.enableDraws && players.isEmpty() && drawWinners.isNotEmpty()) {
                    end(EndingCause.DRAW, drawWinners)
                } else if (Configuration.winnerCelebrationSeconds > 0 && winners.isNotEmpty()) {
                    // The winner stays in survival for the celebration - invincible, unable to
                    // modify the arena, and rescued from the void - instead of being turned into
                    // a spectator. Fireworks + gradient title, then the game officially ends and
                    // everyone returns home.
                    celebrating = true
                    val winner = winners.first()
                    val bukkitWinner = winner.player
                    if (bukkitWinner.isOnline && bukkitWinner.isValid && bukkitWinner.world == world) {
                        winner.winnerProtected = true
                        // The winner keeps their items and position for the celebration - they
                        // only lose the ability to place/break blocks and die, so the arena stays
                        // exactly as the match left it.
                        // Celebrate the top 3 fighters (not only the winner) with fireworks.
                        initialPlayers.sortedByDescending { it.kills }.take(3).forEach { top ->
                            val pb = top.player
                            if (pb.isOnline && pb.isValid && pb.world == world)
                                spawnFireworks(pb.location)
                        }
                    }
                    val winLocale = initialPlayers.firstOrNull()?.locale() ?: Locale.US
                    target(false).showTitle(Title.title(
                        MINI_MESSAGE.deserialize(winLocale.string("info.end.win.title", bukkitWinner.name.escapeTags())),
                        MINI_MESSAGE.deserialize(winLocale.string("info.end.win.subtitle")),
                    ))
                    target(false).playSoundSafe(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)

                    bukkitRunLater(Configuration.winnerCelebrationSeconds * 20L) {
                        if (!ending) end(EndingCause.LAST_STANDING, winners)
                    }
                } else {
                    end(EndingCause.LAST_STANDING, winners)
                }
            }

            // If the game ended (either here or by another cause like timeout), end()'s cleanup already
            // restores every player - so skip the spectator teleport, which would otherwise override it
            // and leave the deciding player stranded in the game world. Same for players who left the
            // game world (their world-leave already eliminated and restored them).
            if (ending) return@bukkitRunLater
            val bukkitPlayer = player.player
            if (!bukkitPlayer.isOnline || !bukkitPlayer.isValid || bukkitPlayer.world != world) return@bukkitRunLater

            if (Configuration.respawnAtConfig) {
                bukkitPlayer.gameMode = Configuration.spawnGameMode
                bukkitPlayer.teleport(Configuration.getSpawnLocation(bukkitPlayer.world))
            } else {
                bukkitPlayer.gameMode = GameMode.SPECTATOR
                bukkitPlayer.teleport(safeSpectatorSpot())
                // Spectators leave the loot behind: the game inventory is wiped so nothing from
                // the match carries over (and no more items will ever drop for them anyway).
                bukkitPlayer.inventory.clear()
                bukkitPlayer.inventory.setItem(8, ItemStack(Material.COMPASS).apply {
                    val meta = itemMeta
                    meta.displayName(bukkitPlayer.locale().component("spectator.menu.title", NamedTextColor.AQUA))
                    itemMeta = meta
                })
            }
        }
    }

    override fun tick(tick: Ticking.Tick) {
        if (ending) return

        // Everyone left (or disconnected) before the game could finish - or the game was started
        // without any players at all: shut it down so the world and arena get cleaned up instead
        // of leaking in GameManager (and locking the queue) forever. While a last-standing/draw
        // resolution is still pending (the final elimination just happened), let it run first.
        if (players.isEmpty() && !winResolutionPending && !ending) {
            end(EndingCause.FORCE)
            return
        }

// Multi Mode: the picker opens the moment the game starts, so nobody waits
// through the first item countdown before they can vote. The game freezes in the
// selection phase until every player picked their modifiers (or closed the menu). Only
// then do the cages open and the item cycle start.
        if (multiSelect && !multiSelectionDone) {
            if (!multiMenuOpened) {
                multiMenuOpened = true
                multiSelectionStartedTick = Bukkit.getCurrentTick()
                players.forEach { QueueEvents.openMultiMenu(it.player) }
            }
            // Activate once everyone alive confirmed - or after 60 seconds no matter what, so a
            // player stuck in their menu can never stall the match forever.
            if (multiSelectionReady() || Bukkit.getCurrentTick() - multiSelectionStartedTick >= 60 * 20)
                activateMultiSelection()
        }

        // While the multi vote is pending the match is frozen: the item countdown must
        // not run down, so the cages can never open (and the fight can never start) while a player
        // is still stuck in the modifier picker.
        if (tick.isSecond(startingTick) && !(multiSelect && !multiSelectionDone)) {
            val countdown = itemCountdown.get()
            if (countdown <= 0) {
                if (!spawnCagesReleased) {
                    releaseCages()
                }

                // The fight is over once the celebration starts: no more item drops, modifier
                // cycles or sound feedback - the winner just watches the fireworks.
                if (!celebrating) {
                    modifiers.forEach { it.onItemCycle() }
                    itemEvents.forEach { it() }

                    // One player's broken item must never abort the drops for the rest of the match.
                    players.forEach { p ->
                        runCatching { addItem(p) }
                            .onFailure { e -> error("Could not give items to ${p.name()}.", e) }
                    }
                    itemCountdown.set(itemCountdown())
                }
            } else {
                players.playSoundSafe(Sound.UI_BUTTON_CLICK, 0.2f, 2.0f) {
                    itemCountdown.get() <= Configuration.soundEffectsCooldown
                }
                // Animated pre-fight countdown: a big 3 / 2 / 1 title in the final seconds
                // before the cages open, so the start feels like a real drop instead of a silent wait.
                if (!started && countdown <= 3) {
                    players.forEach { p ->
                        p.player.showTitle(Title.title(
                            MINI_MESSAGE.deserialize("<red><bold>${countdown}</bold>"),
                            Component.empty()
                        ))
                    }
                }
            }
            itemCountdown.dec()

            // The round timer only runs during the actual fight: the cage/countdown phase is not
            // part of the match, so it must not eat into the players' time.
            if (started)
                timeLeft.dec()

            // No winner by the time the limit is reached: the game simply ends. The winner
            // celebration overrides the timer so a last-second victory always plays out.
            if (timeLeft.get() <= 0 && !celebrating)
                end(EndingCause.TIME_OVER)

            // A single-player game cannot be played: declare the solo player the winner right away.
            if (initialPlayers.size <= 1 && !celebrating)
                end(EndingCause.LAST_STANDING, initialPlayers)
        }

        tickEvents.filter { tick.isInInterval(startingTick, it.value) }.forEach { it.key() }

        // Modifiers only act while the fight is actually running: not during the cage/countdown
        // phase, and not during the winner celebration or end sequence.
        if (started && !celebrating && !ending)
            modifiers.forEach { it.tick(tick) }

        // Containment: players who escape the border - over the wall's top or past its radius -
        // are eliminated, so flying/building out of bounds can never be a strategy. Eliminating
        // (instead of teleporting back) keeps the match honest: leaving the arena is a mistake.
        if (started && !celebrating && !ending && border != null) {
            players.filter { it.player.isOnline && border!!.isOutOfBounds(it.player.location) }
                .forEach { eliminate(it) }
        }

        // The Super Star shield swirls as a galaxy spiral around the shielded player: two
        // rotations with full particle density at 2 charges, one at 1 charge. When the shield's
        // 30 seconds run out it shatters audibly - exactly once, because the charges are reset
        // on expiry (the break on damage is handled in PlayerEvents.onDamage).
        if (started && tick.number % 3 == 0) { // Throttled: particles every 3rd tick instead of every tick.
            val tickCount = Bukkit.getCurrentTick()
            players.forEach { p ->
                if (p.starShieldHits > 0 && !p.starShieldActive) {
                    p.starShieldHits = 0
                    p.player.playSoundSafe(Sound.ITEM_SHIELD_BREAK, 1.0f, 1.0f)
                    p.player.sendActionBar(p.player.locale().component("special.super-star.depleted", color = NamedTextColor.GOLD))
                    return@forEach
                }
                if (!p.starShieldActive) return@forEach

                val turns = p.starShieldHits
                val points = turns * 12
                val phase = tickCount * 0.25
                val loc = p.player.location
                for (i in 0 until points) {
                    val angle = phase + Math.TAU * turns * (i.toDouble() / points)
                    val x = loc.x + cos(angle) * 0.8
                    val z = loc.z + sin(angle) * 0.8
                    val y = loc.y + 0.4 + (i.toDouble() / points) * 1.4
                    p.player.world.spawnParticle(Particle.PORTAL, x, y, z, 0)
                    p.player.world.spawnParticle(Particle.END_ROD, x, y, z, 0)
                }
            }
        }
    }

    // Opens the cages, refills the players' stats and spawns the invisible border cylinder around
    // the arena - the actual start of the fight.
    private fun releaseCages() {
        spawnCagesReleased = true
        fightStartTick = Bukkit.getCurrentTick()
        Cage.clearGameCages()

        border = GameBorder(this, borderAnchor()).also { it.place() }

        players.forEach { p ->
            val pl = p.player
            // The start of the actual fight: refill the stats and clear leftover
            // teleport invulnerability ticks, so hits land from the very first second.
            refill(pl)
            pl.noDamageTicks = 0

            p.showTitle(Title.title(
                p.locale().component("game.start.go", color = NamedTextColor.GREEN),
                p.locale().component("game.cages.open", color = NamedTextColor.YELLOW)
            ))
        }
        players.playSoundSafe(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 2.0f)
    }

    // The border cylinder is anchored on the spectator-spawn column (same as the play area), with
    // the spectator spawn's height as its vertical center.
    private fun borderAnchor(): Location {
        val anchor = arenaAnchor()
        val spectator = map?.spectatorSpawn
        val y = spectator?.y?.toDouble() ?: center.y
        return if (anchor != null) Location(world, anchor.first + 0.5, y, anchor.second + 0.5)
        else Location(world, center.x, y, center.z)
    }

    // ================ MULTI MODIFIER SELECTION ================

    fun toggleMultiModifier(player: Player, name: String) {
        val set = multiSelections.getOrPut(player.uniqueId) { mutableSetOf() }
        if (!set.add(name)) set.remove(name)
    }

    fun confirmMultiSelection(player: Player) {
        // Idempotent: confirming via the menu button, closing the menu, and the hard timeout all
        // call this - the player must only be counted once.
        if (!multiConfirmed.add(player.uniqueId)) return
        multiSelections.getOrPut(player.uniqueId) { mutableSetOf() }
    }

    // Activates every modifier that at least one player voted for. The order follows the registry
    // so the match always gets deterministic modifier ordering (e.g. lava always before UHC).
    private fun activateMultiSelection() {
        if (ending || started || multiSelectionDone) return
        val selected = multiSelections.values.flatten().toSet()
        modifiers = Registry.modifiers
            .filter { it.key in selected }
            .map { it.value.constructModifier(this) }
        modifiers.forEach { m ->
            runCatching { m.init() }.onFailure { e -> error("Could not initialize the ${m.info.namespace} modifier.", e) }
            runCatching { m.customBuild() }.onFailure { e -> error("Could not build the ${m.info.namespace} modifier.", e) }
        }
        multiSelectionDone = true
        // Force-close any menu still open, so nobody is stuck inside the picker when the cages open.
        players.forEach { p -> p.player.closeInventory() }
        players.forEach { p ->
            p.sendMessage(p.locale().chatComponent("vote.multi.activated", selected.joinToString { p.locale().string("modifier.$it.name") }))
        }
        info("Multi modifier selection done, activated: ${selected.joinToString()}")
    }

    // A firework burst at the given location: several rockets with random burst types, detonating
    // shortly after launch.
    private fun spawnFireworks(location: Location, count: Int = 5) {
        repeat(count) {
            val firework = world.spawn(location.clone().add((Math.random() - 0.5) * 2.0, 1.0, (Math.random() - 0.5) * 2.0), Firework::class.java)
            val meta = firework.fireworkMeta
            meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.values().random())
                .withColor(Color.fromRGB(0xFFD700), Color.fromRGB(0xFF4500), Color.fromRGB(0x00BFFF))
                .withFade(Color.WHITE)
                .build())
            meta.power = 1
            firework.fireworkMeta = meta
            bukkitRunLater(2L) { if (firework.isValid) firework.detonate() }
        }
    }

    // Removes dropped items that have been on the ground for more than 30 seconds, to keep long games
    // from accumulating lag. Only runs on pasted arena maps, since without bounds there is no way to
    // tell which items belong to this game, and sweeping the whole world would wipe unrelated drops.
    private fun removeDroppedItems() {
        val bounds = arenaBounds ?: return
        sweepArena(bounds, minTicksLived = 30 * 20, onlyItems = true)
    }

    // Removes every non-player entity (mobs, TNT, items, arrows, ...) inside the given area, so
    // nothing spawned during the game survives into the next one. Scans loaded chunks only, so
    // cleanup never forces distant chunks into memory and the work is bounded per chunk.
    private fun sweepArena(bounds: MapBounds, minTicksLived: Int = 0, onlyItems: Boolean = false) {
        val minX = bounds.minX; val maxX = bounds.maxX
        // Items can fall through the arena into the void below it: sweep down to the world floor
        // so nothing accumulates underneath the play area.
        val minY = bounds.minY.coerceAtMost(world.minHeight)
        val maxY = bounds.maxY
        val minZ = bounds.minZ; val maxZ = bounds.maxZ
        for (cx in (minX shr 4)..(maxX shr 4)) {
            for (cz in (minZ shr 4)..(maxZ shr 4)) {
                if (!world.isChunkLoaded(cx, cz)) continue
                world.getChunkAt(cx, cz).entities.forEach { entity ->
                    if (entity is Player) return@forEach
                    if (onlyItems && entity !is Item) return@forEach
                    if (entity is Item && entity.ticksLived < minTicksLived) return@forEach
                    val loc = entity.location
                    if (loc.blockX !in minX..maxX || loc.blockY !in minY..maxY || loc.blockZ !in minZ..maxZ) return@forEach
                    entity.remove()
                }
            }
        }
    }

    // Persists the outcome of the match to each participant's stats (games played, win/loss, win
    // streak) and hands out win rewards. Runs exactly once, guarded by the `ending` flag in end().
    private fun recordResults(cause: EndingCause, winners: List<PillarPlayer>) {
        val winnerUuids = winners.map { it.uuid() }.toSet()
        for (p in initialPlayers) {
            val uuid = p.uuid()
            PlayerStats.addGame(uuid)
            if (uuid in winnerUuids) {
                PlayerStats.addWin(uuid)
                val pl = p.player
                if (pl.isOnline) {
                    Achievements.grant(pl, "first_win")
                    if (PlayerStats.get(uuid).gamesPlayed >= 25) Achievements.grant(pl, "veteran")
                }
                rewardWin(p)
            } else {
                PlayerStats.addLoss(uuid)
            }
        }
        PlayerStats.saveAll()
    }

    // Win rewards: an optional Vault payout plus config-defined console commands (with %player%).
    private fun rewardWin(p: PillarPlayer) {
        val amount = Configuration.rewardWinAmount
        if (amount > 0 && Hooks.hasEconomy) Hooks.deposit(p.player, amount)
        val name = if (p.player.isOnline) p.player.name else p.uuid().toString()
        Configuration.rewardWinCommands.forEach { cmd ->
            FortunePillars.sendCommand(cmd.replace("%player%", name))
        }
    }

    fun end(cause: EndingCause, winners: List<PillarPlayer> = listOf()) {
        if (ending) return
        ending = true

        recordResults(cause, winners)

        for (p in initialPlayers) {
            val pl = p.player
            if (!pl.isOnline) continue
            pl.closeInventory()
        }

        try {
            // The winner's void-rescue (and the block-protection) only exist for the celebration: the
            // flag must never outlive the game, or the winner would keep getting teleported back into
            // the void by onPlayerMove forever after the match ended.
            initialPlayers.forEach { it.winnerProtected = false }
            for (p in initialPlayers) {
                if (!p.player.isOnline) continue
                when (cause) {
                    EndingCause.FORCE -> p.showTitle(Title.title(
                        p.locale().chatComponent("info.end.force.title"),
                        p.locale().chatComponent("info.end.force.subtitle")
                    ))
                    EndingCause.TIME_OVER -> p.showTitle(Title.title(
                        p.locale().chatComponent("info.end.time-over.title"),
                        p.locale().chatComponent("info.end.time-over.subtitle")
                    ))
                    EndingCause.LAST_STANDING -> p.showTitle(Title.title(
                        p.locale().chatComponent("info.end.last-standing.title", winners.joinToString(" & ") { it.player.name.escapeTags() }),
                        p.locale().chatComponent("info.end.last-standing.subtitle", winners.sumOf { it.kills }.toString())
                    ))
                    EndingCause.DRAW -> p.showTitle(Title.title(
                        p.locale().chatComponent("info.end.draw.title"),
                        p.locale().chatComponent("info.end.draw.subtitle", winners.joinToString(" & ") { it.player.name.escapeTags() })
                    ))
                    EndingCause.ERROR -> p.showTitle(Title.title(
                        p.locale().chatComponent("info.end.error.title"),
                        p.locale().chatComponent("info.end.error.subtitle")
                    ))
                }

                // The stats header matches the ending cause instead of always claiming "Top Players"
                // (which is wrong for a draw or a forced stop).
                val statsKey = "info.end.${cause.name.lowercase()}.stats"
                val statsHeader = p.locale().component(statsKey)
                // If the translation is missing for the player's locale, the serialized text equals
                // the raw key - fall back to a sensible default.
                val headerComponent = if (MINI_MESSAGE.serialize(statsHeader) == statsKey)
                    component("Top Players")
                else
                    statsHeader
                p.sendMessage(component("=== ").append(headerComponent).append(component(" ===")).color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                    initialPlayers.sortedByDescending { it.kills }.forEachIndexed { i, sorted ->
                        p.sendMessage(miniMessage("<dark_gray>${i + 1}. <gray>${sorted.player.name.escapeTags()} <dark_gray>(<gold>${sorted.kills}<red>⚔<dark_gray>)"))
                    }

                    // One-click re-queue: a clickable message so players don't have to reopen the menu.
                    p.sendMessage(p.locale().component("game.end.play-again").clickEvent(ClickEvent.runCommand("/pp queue join")))
            }

            when (cause) {
                EndingCause.FORCE -> warn("Stopped game forcefully.")
                EndingCause.TIME_OVER -> info("Stopped game because the time is up.")
                EndingCause.LAST_STANDING -> info("Stopped game because ${winners.joinToString()} won.")
                EndingCause.DRAW -> info("Stopped game because ${winners.joinToString(" & ")} died at the same time, resulting in a draw.")
                EndingCause.ERROR -> error("Stopped game due to an error. Error code: #001")
            }

            Configuration.endingCommands.forEach { FortunePillars.sendCommand(it(
                "id" to id,
                "mode" to info.mode.gameInfo.namespace,
                "players" to initialPlayers.size,
                "cause" to cause.name.lowercase(),
                "world" to center.world.name,
                "x" to center.x,
                "y" to center.y,
                "z" to center.z,
            )) }
            cleanup()
        } finally {
            // Always send players back to the lobby spawn after a game, never the game world and
            // never their pre-game spot: the snapshot restore in cleanup() already put them at
            // their old location, so this re-teleports everyone to the configured spawn and
            // re-anchors their respawn point there - a later death or rejoin can't land them at
            // a stale position either. Each player is guarded so one failure (e.g. an offline
            // player) can't strand the others in the game world.
            val sentBack = mutableSetOf<UUID>()
            val sendBack = {
                initialPlayers.forEach {
                    val pl = it.player
                    // Already sent home by the earlier pass: the delayed safety net must not yank
                    // a player who walked off the spawn point back to it a second time.
                    if (pl.uniqueId in sentBack) return@forEach
                    // AUTO-queued players get re-added to the queue by their own restore; don't yank
                    // them out of the queue arena when the delayed send-back fires later. Players who
                    // already joined another match (queue or manual) are guarded the same way: a stale
                    // send-back must never teleport them out of a running game.
                    if (pl.isOnline && pl !in QueueManager.queue && !GameManager.isInGame(pl, onlyAlive = false))
                        runCatching {
                            val lobby = Configuration.getLobbySpawn()
                            pl.teleport(lobby)
                            pl.respawnLocation = lobby
                            sentBack += pl.uniqueId
                        }
                }
            }
            // Fire immediately so nobody waits at their old spot after the match, then again after
            // the configured delay as an idempotent safety net for anyone the first pass missed.
            sendBack()
            if (Configuration.timeAfterGame > 0)
                bukkitRunLater(Configuration.timeAfterGame * 20L) { sendBack() }
        }
    }

    private fun cleanup() {
        GameManager.remove(this)
        // Restore every player individually; an offline or otherwise un-cleanable player must not
        // abort the cleanup for the rest of them (or skip the arena reset below).
        initialPlayers.forEach { runCatching { it.clear(true) } }
        // Game cages are normally cleared when the cages open; a game ending before that (during
        // the countdown, multi selection or an early force-stop) must clear them here instead.
        // This must run BEFORE the buildings reset: the cages sit on the pasted arena floor, so
        // clearing them first and resetting after restores the floor instead of punching holes
        // into the freshly-reset arena.
        runCatching { Cage.clearGameCages() }
        runCatching { buildings.reset() }
        // Authoritative arena re-paste on the dedicated queue world: when this is the last game
        // still using the world, re-paste the current arena so the next round starts from a pristine
        // schematic rather than relying solely on the change-tracking reset above. Guarded so a
        // failure can never break cleanup - the change-tracking reset is the real safety net.
        if (Configuration.arenaResetRePaste
            && world.name == Cage.queueWorldName
            && (worldUsers[world] ?: 0) <= 1) {
            runCatching { QueueManager.rePasteCurrentArena() }
                .onFailure { org.bukkit.Bukkit.getLogger().warning("Arena re-paste failed; relying on change-tracking reset.") }
        }
        bossBar?.stop()
        // The barrier cylinder sits outside the arena wipe radius, so it removes its own blocks
        // (in batches, so the end of a match never spikes the server).
        runCatching { border?.remove() }
        // A failing modifier teardown (e.g. a chunk-snapshot scan) must not abort the cleanup and
        // strand the players in the game world.
        modifiers.sortedByDescending { it.teardownOrder }.forEach { m -> runCatching { m.onEnd() }.onFailure { error("Could not stop the ${m.info.namespace} modifier.", it) } }
        // Restore the world settings captured at init, after the modifiers had their say. Only the
        // last game still using this world restores them, so concurrent games don't clobber each
        // other's settings.
        if (worldStateSaved) {
            val remaining = (worldUsers[world] ?: 1) - 1
            if (remaining <= 0) worldUsers.remove(world) else worldUsers[world] = remaining
            if (remaining <= 0) {
                world.setGameRuleSafe("DO_IMMEDIATE_RESPAWN", "IMMEDIATE_RESPAWN", savedImmediateRespawn)
                world.setGameRuleSafe("DO_MOB_SPAWNING", "SPAWN_MOBS", savedMobSpawning)
                world.setGameRuleSafe("NATURAL_REGENERATION", "NATURAL_HEALTH_REGENERATION", savedNaturalRegen)
                world.setPVP(savedPvP)
                worldStateSaved = false
            }
        }
        // Sweep any dropped items and leftover mobs from the arena, so the map is clean for the next round.
        runCatching { sweepArena(arenaBounds ?: playArea(60)) }
        // On the dedicated queue world, mobs (op-mode bosses), TNT and arrows can wander far beyond
        // the arena bounds before the match ends: wipe every non-player entity so nothing carries
        // over into the next game. Shared admin worlds are skipped so unrelated mobs survive.
        if (world.name == Cage.queueWorldName)
            runCatching { world.getEntities().forEach { if (it !is Player) it.remove() } }

        // Unregister every event listener this game registered, so handlers never fire into a dead
        // game (mirrors the listener registry added alongside this).
        registeredListeners.forEach { org.bukkit.event.HandlerList.unregisterAll(it) }
        // Cancel every scheduled task this game scheduled, so delayed callbacks never run after the
        // game ended (mirrors the task registry added alongside this).
        scheduledTasks.forEach { it.cancel() }
    }
}
