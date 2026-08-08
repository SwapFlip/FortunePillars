package com.marcpg.pillarperil.game

import com.marcpg.libpg.data.time.Time
import com.marcpg.libpg.display.*
import com.marcpg.libpg.util.Randomizer
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.miniMessage
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.game.util.Cage
import com.marcpg.pillarperil.game.util.GameInfo
import com.marcpg.pillarperil.game.util.GameManager
import com.marcpg.pillarperil.game.util.QueueManager
import com.marcpg.pillarperil.generation.Buildings
import com.marcpg.pillarperil.map.ArenaMap
import com.marcpg.pillarperil.map.BlockPos
import com.marcpg.pillarperil.map.MapBounds
import com.marcpg.pillarperil.map.MapManager
import com.marcpg.pillarperil.map.MapPaster
import com.marcpg.pillarperil.map.SchematicReader
import com.marcpg.pillarperil.player.PillarPlayer
import com.marcpg.pillarperil.util.*
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.*
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale
import java.util.UUID
import io.papermc.paper.scoreboard.numbers.NumberFormat
import kotlin.math.atan2

abstract class Game(
    val id: String,
    center: Location,
    protected val bukkitPlayers: List<Player>,
    var modifiers: List<GameModifier>,
): Ticking {
    enum class EndingCause {
        FORCE,
        TIME_OVER,
        LAST_STANDING,
        DRAW,
        ERROR,
    }

    companion object {
        private val itemNowColor = listOf(TextColor.color(0x00AA22), TextColor.color(0x11FF77))
        private val itemTimeColor = listOf(TextColor.color(0x0022FF), TextColor.color(0x3399FF))

        fun getColor(left: Float): BossBar.Color = when {
            left < 0.4 -> BossBar.Color.RED
            left < 0.6 -> BossBar.Color.YELLOW
            left < 0.8 -> BossBar.Color.GREEN
            else -> BossBar.Color.PINK
        }

        fun generateId() = Randomizer.generateRandomString(Constants.GAME_ID_LENGTH, Constants.GAME_ID_CHARSET)
    }

    // ================ CONSTRUCTION DATA ================

    abstract val info: GameInfo

    val center: Location = center.clone().apply { y = Configuration.platformHeight + 1.0 }
    val world: World = center.world
    val startingTick: Int = Bukkit.getCurrentTick()

    val initialPlayers: List<PillarPlayer> = mutableListOf() // Only modified at startup, hence hidden mutability.
    val players = mutableListOf<PillarPlayer>()

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
    // center, so hazards like lava still work.
    fun playArea(size: Int): MapBounds {
        val bounds = arenaBounds
        if (bounds != null) {
            val (centerX, centerZ) = arenaAnchor() ?: ((bounds.minX + bounds.maxX) / 2 to (bounds.minZ + bounds.maxZ) / 2)
            val half = size / 2
            return MapBounds(centerX - half, bounds.minY, centerZ - half, centerX + half, bounds.maxY, centerZ + half)
        }

        val half = size / 2
        val cx = center.blockX
        val cz = center.blockZ
        return MapBounds(cx - half, center.blockY - half, cz - half, cx + half, center.blockY + half, cz + half)
    }

    // The horizontal center of the out-of-bounds radius: the same anchor as the play area.
    private fun radiusCenter(): Pair<Int, Int> = arenaAnchor() ?: (center.blockX to center.blockZ)

    // ==================== GAME STATE ====================

    val timeLeft = Time()
    val itemCountdown = Time(0, allowNegatives = true)

    private var spawnCagesReleased = false

    // The game has started once the cages release. Damage and inventory locking key off this
    // instead of the item countdown, which keeps cycling for the entire match.
    val started: Boolean get() = spawnCagesReleased

    // Out-of-bounds radius mechanic: the safe zone around the spectator spawn (radiusCenter()).
    // Once the game time runs out without a winner the game enters shrink mode and the radius
    // closes in every second, forcing a confrontation instead of ending the game outright.
    private var shrinkMode = false
    private var currentRadius: Double = Configuration.radiusBase.toDouble()

    val deathHeight: Double get() = map?.deathHeight?.toDouble() ?: Configuration.deathHeight

    fun itemCountdown(): Long = customItemCountdown?.invoke() ?: info.itemCountdown()

    fun typeName(locale: Locale): String = modifiers.firstOrNull()?.info?.name(locale) ?: "-"

    val itemCountdownPercentage: Float
        get() = (itemCountdown.get().toFloat() / (itemCountdown().toFloat() - 1)).coerceIn(0.0f, 1.0f)

    private val tickEvents = mutableMapOf<() -> Unit, Int>()
    private val itemEvents = mutableListOf<() -> Unit>()

    var ending = false

    // ================= DISPLAY METHODS =================

    open val scoreboard: ((PillarPlayer) -> SimpleScoreboard)? = { p -> createScoreboard(p) }

    private fun createScoreboard(p: PillarPlayer): SimpleScoreboard {
        val title = MiniMessage.miniMessage().deserialize(Configuration.scoreboardTitle)

        if (Configuration.scoreboardLines.isNotEmpty()) {
            val entries = Configuration.scoreboardLines.map { line ->
                PlaceholderScoreboardEntry { _ ->
                    MiniMessage.miniMessage().deserialize(
                        line.replace("<mode>", info.name(p.locale()))
                            .replace("<type>", typeName(p.locale()))
                            .replace("<map>", map?.name ?: "-")
                            .replace("<player>", p.name())
                            .replace("<time>", timeLeft.oneUnitFormatted)
                            .replace("<kills>", p.kills.toString())
                            .replace("<alive>", players.size.toString())
                            .replace("<countdown>", itemCountdown.oneUnitFormatted)
                    )
                }
            }.toTypedArray()
            return SimpleScoreboard(p, 5, title, *entries)
        }

        return SimpleScoreboard(p, 5, title,
            StaticValueScoreboardEntry(p.locale().component("scoreboard.mode").style(info.keyStyle()), component(info.name(p.locale())).style(info.valueStyle)),
            StaticValueScoreboardEntry(p.locale().component("scoreboard.name").style(info.keyStyle()), component(p.name()).style(info.valueStyle)),
            ValueScoreboardEntry(p.locale().component("scoreboard.time").style(info.keyStyle())) { component(timeLeft.oneUnitFormatted).style(info.valueStyle) },
            ValueScoreboardEntry(p.locale().component("scoreboard.kills").style(info.keyStyle())) { component(p.kills.toString()).style(info.valueStyle) },
        )
    }

    private inner class PlaceholderScoreboardEntry(private val text: (Locale) -> Component) : ScoreboardEntry() {
        override fun init(index: Int, board: SimpleScoreboard) {
            super.init(index, board)
            score.numberFormat(NumberFormat.blank())
        }

        override fun update(board: SimpleScoreboard) {
            score.customName(text(board.receiver.locale()))
        }
    }

    open val actionBar: ((PillarPlayer) -> SimpleActionBar)? = { p -> GradientActionBar(p, 5, 0.1) {
        when {
            itemCountdown.get() in 1..3 -> p.locale().component("game.start.countdown", itemCountdown.get().toString(), color = NamedTextColor.GRAY) to itemTimeColor
            itemCountdown.get() == 0L -> p.locale().component("actionbar.now") to itemNowColor
            else -> p.locale().component("actionbar.time", itemCountdown.preciselyFormatted) to itemTimeColor
        }
    } }

    open val bossBarCreator: () -> SimpleBossBar = { SimpleBossBar(target(false),
        20,
        { _ ->
            if (itemCountdown.get() <= 0L)
                component("Next item is ready!").color(NamedTextColor.GREEN)
            else
                component("Next item in ${itemCountdown.oneUnitFormatted}").style(info.keyStyle())
        },
        { itemCountdownPercentage },
        { getColor(itemCountdownPercentage) },
        { BossBar.Overlay.NOTCHED_10 }
    ) }

    var bossBar: SimpleBossBar? = null
        private set

    // =============== OVERRIDABLE METHODS ===============

    open fun init() {
        world.setGameRuleSafe("DO_IMMEDIATE_RESPAWN", "IMMEDIATE_RESPAWN", true)
        world.setGameRuleSafe("DO_MOB_SPAWNING", "TRUE", true)
        world.setGameRuleSafe("NATURAL_REGENERATION", "NATURAL_REGENERATION", true)
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
                it.player.setInvulnerable(true)
                refill(it.player)
            }
            .forEach {
                (initialPlayers as MutableList) += it
                players += it
            }

        val isEnabledMethod = runCatching {
            World::class.java.getMethod("isEnabled", Class.forName("io.papermc.paper.world.flag.FeatureDependant"))
        }.getOrNull()

        @Suppress("DEPRECATION")
        val enabledCheck: (Material) -> Boolean = { material ->
            if (isEnabledMethod != null) {
                isEnabledMethod.invoke(world, material) as Boolean
            } else {
                material.isEnabledByFeature(world)
            }
        }

        items = buildItems(enabledCheck)

        radius = initialPlayers.size * Configuration.platformDistanceFactor / Math.TAU

        modifiers.forEach { it.init() }

        buildings = Buildings(this, info.horGen().constructGen(this), info.vertGen().constructGen(this))

        val map = this.map
        when {
            map != null && arenaBounds != null -> info("Playing on pre-pasted arena map \"${map.name}\".")
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
        val pool = Configuration.itemsPool
        if (pool.isEmpty())
            return Registry.MATERIAL.filter { it !in Configuration.itemsBlacklist && enabledCheck(it) && info.additionalFilter(it) }.toList()

        return pool.flatMap { (name, weight) ->
            val material = Material.matchMaterial(name)
            if (material != null && material !in Configuration.itemsBlacklist && enabledCheck(material) && info.additionalFilter(material))
                List(weight) { material }
            else
                emptyList()
        }
    }

    private fun startOnMap(map: ArenaMap) {
        val file = MapManager.schematicFile(map.schematic)
        val schematic = file?.let { runCatching { SchematicReader.read(it) }.getOrNull() }

        if (schematic == null) {
            error("Could not read schematic \"${map.schematic}\" for map \"${map.name}\", falling back to default generation.", IllegalStateException("Missing or invalid schematic file."))
            buildAndTeleport()
            return
        }

        arenaBounds = MapPaster.paste(schematic, world, map.origin)

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

    open fun addItem(player: PillarPlayer) = player.giveItems(items)

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

    fun info(msg: String) = PillarPeril.LOG.info("[${Constants.GAME_LOG_PREFIX}$id] $msg")
    fun warn(msg: String) = PillarPeril.LOG.warn("[${Constants.GAME_LOG_PREFIX}$id] $msg")
    fun error(msg: String, e: Throwable) = PillarPeril.LOG.error("[${Constants.GAME_LOG_PREFIX}$id] $msg", e)

    protected fun addTickEvent(interval: Time, event: () -> Unit) = addTickEvent(interval.get() * 20L, event)

    protected fun addTickEvent(intervalTicks: Long, event: () -> Unit) {
        tickEvents[event] = intervalTicks.toInt()
    }

    protected fun addItemEvent(event: () -> Unit) {
        itemEvents.add(event)
    }

    fun target(onlyAlive: Boolean = true): MinecraftReceiver = if (onlyAlive) players.receiver() else initialTarget

    fun player(bukkitPlayer: Player, onlyAlive: Boolean = true): PillarPlayer? {
        for (player in (if (onlyAlive) players else initialPlayers)) {
            if (player.uuid() == bukkitPlayer.uniqueId)
                return player
        }
        return null
    }

    fun player(uuid: UUID, onlyAlive: Boolean = true): PillarPlayer? {
        for (player in (if (onlyAlive) players else initialPlayers)) {
            if (player.uuid() == uuid)
                return player
        }
        return null
    }

    // ================ GAME-LOGIC METHODS ================

    fun eliminate(player: PillarPlayer) {
        if (ending || player !in players) return

        players -= player
        info("$player got eliminated.")

        player.deathTime = Bukkit.getCurrentTick()

        modifiers.forEach { it.onPlayerDeath(player) }

        val win = players.size <= 1
        val winners = players.toList()

        bukkitRunLater(19) { // 0.95s / 950ms
            if (!ending && win) {
                val lastDeath = initialPlayers.mapNotNull { it.deathTime }.max()
                val drawWinners = initialPlayers.filter { (it.deathTime ?: Int.MIN_VALUE) + 19 >= lastDeath }

                if (Configuration.enableDraws && players.isEmpty() && drawWinners.isNotEmpty()) {
                    end(EndingCause.DRAW, drawWinners)
                } else {
                    end(EndingCause.LAST_STANDING, winners)
                }
            }

            // If the game ended (either here or by another cause like timeout), end()'s cleanup already
            // restores every player - so skip the spectator teleport, which would otherwise override it
            // and leave the deciding player stranded in the game world.
            if (ending) return@bukkitRunLater
            val bukkitPlayer = player.player
            if (!bukkitPlayer.isOnline || !bukkitPlayer.isValid) return@bukkitRunLater

            if (Configuration.respawnAtConfig) {
                bukkitPlayer.gameMode = Configuration.spawnGameMode
                bukkitPlayer.teleport(Configuration.getSpawnLocation(bukkitPlayer.world))
            } else {
                bukkitPlayer.gameMode = GameMode.SPECTATOR
                bukkitPlayer.teleport(map?.spectatorLocation(world) ?: center)
                bukkitPlayer.inventory.setItem(8, ItemStack(Material.COMPASS).apply {
                    val meta = itemMeta
                    meta.displayName(bukkitPlayer.locale().component("spectator.menu.title", NamedTextColor.AQUA))
                    itemMeta = meta
                })
            }

            modifiers.forEach { it.onPostPlayerDeath(player) }
        }
    }

    override fun tick(tick: Ticking.Tick) {
        if (ending) return

        // Everyone left (or disconnected) before the game could finish: shut it down so the world
        // and arena get cleaned up instead of leaking in GameManager forever.
        if (players.isEmpty()) {
            if (initialPlayers.isNotEmpty())
                end(EndingCause.FORCE)
            return
        }

        if (tick.isSecond(startingTick)) {
            val countdown = itemCountdown.get()
            if (countdown <= 0) {
                if (!spawnCagesReleased) {
                    spawnCagesReleased = true
                    Cage.clearGameCages()

                    players.forEach { p ->
                        val pl = p.player
                        // The start of the actual fight: refill the stats, drop the invulnerability
                        // granted in init() and clear any leftover teleport invulnerability ticks,
                        // so hits land from the very first second.
                        refill(pl)
                        pl.setInvulnerable(false)
                        pl.noDamageTicks = 0

                        p.showTitle(Title.title(
                            p.locale().component("game.start.go", color = NamedTextColor.GREEN),
                            p.locale().component("game.cages.open", color = NamedTextColor.YELLOW)
                        ))
                    }
                    players.playSoundSafe(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 2.0f)
                }

                modifiers.forEach { it.onItemCycle() }
                itemEvents.forEach { it() }

                players.forEach { addItem(it) }
                itemCountdown.set(itemCountdown())
            } else {
                players.playSoundSafe(Sound.UI_BUTTON_CLICK, 0.2f, 2.0f) {
                    itemCountdown.get() <= Configuration.soundEffectsCooldown
                }
            }
            itemCountdown.dec()

            timeLeft.dec()

            // No winner by the time limit: instead of ending the game, enter shrink mode and close
            // the out-of-bounds radius in, forcing the remaining players into the center.
            if (timeLeft.get() <= 0 && !shrinkMode) {
                if (players.size > 1 && Configuration.radiusEnabled) {
                    shrinkMode = true
                    players.forEach { p ->
                        p.showTitle(Title.title(
                            p.locale().component("game.shrink.title", color = NamedTextColor.RED),
                            p.locale().component("game.shrink.subtitle", color = NamedTextColor.YELLOW)
                        ))
                    }
                } else {
                    end(EndingCause.TIME_OVER)
                }
            }

            // Shrink phase: the radius closes in every second until it's fully gone.
            if (shrinkMode) {
                currentRadius -= Configuration.radiusShrinkPerSecond
                if (currentRadius <= 0.0) {
                    end(EndingCause.TIME_OVER)
                    return
                }
            }

            // Out-of-bounds damage: anyone horizontally outside the radius loses damage-hearts every
            // second, covering the full column from the void up to the top of the world.
            if (Configuration.radiusEnabled && spawnCagesReleased) {
                val (cx, cz) = radiusCenter()
                val radius = currentRadius
                players.forEach { p ->
                    val loc = p.player.location
                    val dx = loc.blockX - cx
                    val dz = loc.blockZ - cz
                    if (Math.hypot(dx.toDouble(), dz.toDouble()) > radius) {
                        p.player.noDamageTicks = 0
                        p.player.damage(Configuration.radiusDamageHearts * 2.0)
                    }
                }
            }
        }

        tickEvents.filter { tick.isInInterval(startingTick, it.value) }.forEach { it.key() }

        modifiers.forEach { it.tick(tick) }
    }

    // Removes dropped items that have been on the ground for more than 30 seconds, to keep long games
    // from accumulating lag. Only runs on pasted arena maps, since without bounds there is no way to
    // tell which items belong to this game, and sweeping the whole world would wipe unrelated drops.
    private fun removeDroppedItems() {
        val bounds = arenaBounds ?: return
        sweepArenaItems(bounds, minTicksLived = 30 * 20)
    }

    // Removes dropped items inside the given area, so nothing is left littering the arena.
    private fun sweepArenaItems(bounds: MapBounds, minTicksLived: Int) {
        world.getEntities().filterIsInstance<Item>().forEach { item ->
            if (item.ticksLived < minTicksLived) return@forEach
            if (item.location.x < bounds.minX || item.location.x > bounds.maxX ||
                    item.location.y < bounds.minY || item.location.y > bounds.maxY ||
                    item.location.z < bounds.minZ || item.location.z > bounds.maxZ) return@forEach
            item.remove()
        }
    }

    fun end(cause: EndingCause, winners: List<PillarPlayer> = listOf()) {
        if (ending) return
        ending = true

        for (p in initialPlayers) {
            when (cause) {
                EndingCause.FORCE -> p.showTitle(Title.title(
                    p.locale().component("info.end.force.title", color = NamedTextColor.YELLOW),
                    p.locale().component("info.end.force.subtitle", color = NamedTextColor.RED)
                ))
                EndingCause.TIME_OVER -> p.showTitle(Title.title(
                    p.locale().component("info.end.time-over.title", color = NamedTextColor.GREEN),
                    p.locale().component("info.end.time-over.subtitle", color = NamedTextColor.YELLOW)
                ))
                EndingCause.LAST_STANDING -> p.showTitle(Title.title(
                    p.locale().component("info.end.last-standing.title", winners.joinToString(" & "), color = NamedTextColor.GREEN),
                    p.locale().component("info.end.last-standing.subtitle", winners.sumOf { it.kills }.toString(), color = NamedTextColor.YELLOW)
                ))
                EndingCause.DRAW -> p.showTitle(Title.title(
                    p.locale().component("info.end.draw.title", color = NamedTextColor.GREEN),
                    p.locale().component("info.end.draw.subtitle", winners.joinToString(" & "), color = NamedTextColor.YELLOW)
                ))
                EndingCause.ERROR -> p.showTitle(Title.title(
                    p.locale().component("info.end.error.title", color = NamedTextColor.RED),
                    p.locale().component("info.end.error.subtitle", color = NamedTextColor.GRAY)
                ))
            }

            p.sendMessage(component("=== ").append(p.locale().component("info.end.time-over.stats")).append(component(" ===")).color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
            initialPlayers.sortedByDescending { it.kills }.forEachIndexed { i, sorted ->
                p.sendMessage(miniMessage("<dark_gray>${i + 1}. <gray>${sorted.player.name} <dark_gray>(<gold>${sorted.kills}<red>⚔<dark_gray>)"))
            }
        }

        when (cause) {
            EndingCause.FORCE -> warn("Stopped game forcefully.")
            EndingCause.TIME_OVER -> info("Stopped game because the time is up.")
            EndingCause.LAST_STANDING -> info("Stopped game because ${winners.joinToString()} won.")
            EndingCause.DRAW -> info("Stopped game because ${winners.joinToString(" & ")} died at the same time, resulting in a draw.")
            EndingCause.ERROR -> error("Stopped game due to an error. Error code: #001")
        }

        for (p in initialPlayers) {
            val pl = p.player
            if (!pl.isOnline) continue
            pl.closeInventory()
        }

        try {
            Configuration.endingCommands.forEach { PillarPeril.sendCommand(it(
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
            // Always send players back to the main server world after a game, never the game world.
            // Runs after the cleanup so the snapshot restore can't override it. Each player is guarded
            // so one failure (e.g. an offline player) can't strand the others in the game world.
            // When `time-after-game` is set, the leave is delayed so the end titles and kill-cam can
            // play out before players get teleported away.
            val sendBack = {
                initialPlayers.forEach {
                    val pl = it.player
                    // AUTO-queued players get re-added to the queue by their own restore; don't yank
                    // them out of the queue arena when the delayed send-back fires later.
                    if (pl.isOnline && pl !in QueueManager.queue)
                        runCatching { pl.teleport(Configuration.getSpawnLocation(Bukkit.getWorlds().first())) }
                }
            }
            if (Configuration.timeAfterGame > 0)
                bukkitRunLater(Configuration.timeAfterGame * 20L) { sendBack() }
            else
                sendBack()
        }
    }

    private fun cleanup() {
        GameManager.remove(this)
        // Restore every player individually; an offline or otherwise un-cleanable player must not
        // abort the cleanup for the rest of them (or skip the arena reset below).
        initialPlayers.forEach { runCatching { it.clear(true) } }
        runCatching { buildings.reset() }
        bossBar?.stop()
        // A failing modifier teardown (e.g. a chunk-snapshot scan) must not abort the cleanup and
        // strand the players in the game world.
        modifiers.forEach { m -> runCatching { m.onEnd() }.onFailure { error("Could not stop the ${m.info.namespace} modifier.", it) } }
        // Sweep any dropped items and leftover mobs from the arena, so the map is clean for the next round.
        runCatching { sweepArenaItems(arenaBounds ?: playArea(60), minTicksLived = 0) }
        runCatching { sweepArenaEntities(arenaBounds ?: playArea(60)) }
    }

    // Removes all non-player, non-item entities (mobs, TNT, ...) inside the given area, so nothing
    // spawned during the game survives into the next one.
    private fun sweepArenaEntities(bounds: MapBounds) {
        world.getEntities().forEach { entity ->
            if (entity is Player || entity is Item) return@forEach
            val loc = entity.location
            if (loc.blockX !in bounds.minX..bounds.maxX || loc.blockY !in bounds.minY..bounds.maxY || loc.blockZ !in bounds.minZ..bounds.maxZ) return@forEach
            entity.remove()
        }
    }
}
