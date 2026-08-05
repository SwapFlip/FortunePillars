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
            left < 0.2 -> BossBar.Color.RED
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

    // ==================== GAME STATE ====================

    val timeLeft = Time()
    val itemCountdown = Time(0, allowNegatives = true)

    private var spawnCagesReleased = false

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
        if (itemCountdown.get() == 0L)
            p.locale().component("actionbar.now") to itemNowColor
        else
            p.locale().component("actionbar.time", itemCountdown.preciselyFormatted) to itemTimeColor
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

        bukkitPlayers
            .map { PillarPlayer(it, this) }
            .onEach {
                QueueManager.remove(it.player)

                it.player.gameMode = GameMode.SURVIVAL
                it.player.clearActivePotionEffects()
                it.player.inventory.clear()
                it.player.foodLevel = 20
                it.player.saturation = 20.0f

                val maxHealth = it.player.getAttributeSafe("MAX_HEALTH")?.value
                if (maxHealth != null) {
                    it.player.health = maxHealth
                } else {
                    it.player.health = it.player.maxHealth
                }
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

    private fun buildItems(enabledCheck: (Material) -> Boolean): List<Material> {
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

        players.forEachIndexed { i, p ->
            val spawn = map.spawns.getOrNull(i) ?: map.origin
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

            if (Configuration.respawnAtConfig) {
                player.player.gameMode = Configuration.spawnGameMode
                player.player.teleport(Configuration.getSpawnLocation(player.player.world))
            } else {
                player.player.gameMode = GameMode.SPECTATOR
                player.player.teleport(map?.spectatorLocation(world) ?: center)
                player.player.inventory.setItem(8, ItemStack(Material.COMPASS).apply {
                    val meta = itemMeta
                    meta.displayName(player.locale().component("spectator.menu.title", NamedTextColor.AQUA))
                    itemMeta = meta
                })
            }

            modifiers.forEach { it.onPostPlayerDeath(player) }
        }
    }

    override fun tick(tick: Ticking.Tick) {
        if (ending || players.isEmpty()) return

        if (tick.isSecond(startingTick)) {
            val countdown = itemCountdown.get()
            if (countdown <= 0) {
                if (!spawnCagesReleased) {
                    spawnCagesReleased = true
                    Cage.clearGameCages()

                    players.forEach { p ->
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
                if (countdown <= 3)
                    players.forEach { p ->
                        p.showTitle(Title.title(
                            component(countdown.toString(), NamedTextColor.YELLOW).decorate(TextDecoration.BOLD),
                            p.locale().component("game.start.countdown", countdown.toString(), color = NamedTextColor.GRAY)
                        ))
                    }

                players.playSoundSafe(Sound.UI_BUTTON_CLICK, 0.2f, 2.0f) {
                    itemCountdown.get() <= Configuration.soundEffectsCooldown
                }
            }
            itemCountdown.dec()

            timeLeft.dec()
            if (timeLeft.get() <= 0)
                end(EndingCause.TIME_OVER)
        }

        tickEvents.filter { tick.isInInterval(startingTick, it.value) }.forEach { it.key() }

        modifiers.forEach { it.tick(tick) }
    }

    // Removes dropped items that have been on the ground for more than 30 seconds, to keep long games
    // from accumulating lag. The cleanup only touches items that were dropped during this game's arena.
    private fun removeDroppedItems() {
        val bounds = arenaBounds
        world.getEntities().filterIsInstance<Item>().forEach { item ->
            if (item.ticksLived < 30 * 20) return@forEach
            if (bounds != null && (item.location.x < bounds.minX || item.location.x > bounds.maxX ||
                    item.location.y < bounds.minY || item.location.y > bounds.maxY ||
                    item.location.z < bounds.minZ || item.location.z > bounds.maxZ)) return@forEach
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
                    component("Nobody wins!", color = NamedTextColor.RED),
                    component("An error occurred, resulting in no winner.", color = NamedTextColor.GRAY)
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
            pl.gameMode = Configuration.spawnGameMode
            pl.teleport(Configuration.getSpawnLocation(pl.world))
        }

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
    }

    private fun cleanup() {
        GameManager.remove(this)
        initialPlayers.forEach { it.clear(true) }
        buildings.reset()
        bossBar?.stop()
        modifiers.forEach { it.onEnd() }
    }
}
