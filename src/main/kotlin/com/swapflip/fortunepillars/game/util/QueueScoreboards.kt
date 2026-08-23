package com.swapflip.fortunepillars.game.util

import com.marcpg.libpg.display.SimpleScoreboard
import com.marcpg.libpg.display.ScoreboardEntry
import com.marcpg.libpg.display.receiver
import com.marcpg.libpg.display.start
import com.marcpg.libpg.lang.string
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.Registry
import com.swapflip.fortunepillars.map.MapManager
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.MINI_MESSAGE
import com.swapflip.fortunepillars.util.ScoreboardTemplates
import com.swapflip.fortunepillars.util.escapeTags
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID

// The sidebar scoreboard shown to everyone waiting in the queue. Lives exactly as long as the
// player's queue state: shown when a player is added, hidden when they leave (quit, world change,
// queue leave) or when their game starts. Values refresh themselves - every entry re-resolves its
// placeholders on the configured tick interval, so no manual refresh pass is needed.
object QueueScoreboards {
    // Placeholders usable in `queue-scoreboard.lines`. Parsing is restricted to this set so real
    // MiniMessage tags (<gray>, <bold>, ...) in config lines are never mistaken for placeholders.
    private val PLACEHOLDERS = setOf(
        "players", "min", "max", "needed", "slots-left", "countdown", "status", "map",
        "mode-vote", "type-vote", "time-vote", "map-vote", "map-votes", "votes-cast",
        "online", "games", "player", "world", "clock",
    )

    private val boards = mutableMapOf<UUID, SimpleScoreboard>()

    // Vote tallies are global and only change when someone votes, yet `resolve` runs once per
    // placeholder per board update. Recomputing the four tallies on every placeholder is wasteful,
    // so we recompute them at most once per server tick and reuse the result for the rest of it.
    private var cachedTime: Map<Int, Int>? = null
    private var cachedMode: Map<String, Int>? = null
    private var cachedType: Map<String, Int>? = null
    private var cachedMap: Map<String, Int>? = null
    private var cachedTick: Int = -1

    // Recomputes the four vote tallies at most once per server tick. `Bukkit.getCurrentTick()` is
    // stable within a single board update (all placeholders share it) and changes between updates,
    // so the cached maps are reused for every placeholder of every player in a given tick.
    private fun refreshVoteCache() {
        val tick = Bukkit.getCurrentTick()
        if (tick == cachedTick) return
        cachedTime = QueueManager.timeVoteCounts()
        cachedMode = QueueManager.modeVoteCounts()
        cachedType = QueueManager.typeVoteCounts()
        cachedMap = QueueManager.mapVoteCounts()
        cachedTick = tick
    }

    fun show(player: Player) {
        if (!Configuration.queueScoreboardEnabled) return
        if (boards.containsKey(player.uniqueId)) return

        val interval = Configuration.queueScoreboardUpdateInterval.toLong()
        val title = MINI_MESSAGE.deserialize(Configuration.queueScoreboardTitle)
        val showNumbers = Configuration.queueScoreboardShowNumbers

        val entries = if (Configuration.queueScoreboardLines.isNotEmpty()) {
            Configuration.queueScoreboardLines.map { line ->
                val template = ScoreboardTemplates.parse(line, PLACEHOLDERS)
                ScoreboardTemplates.TemplateEntry(template, showNumbers) { locale, key -> resolve(key, player, locale) }
            }
        } else {
            defaultEntries(player)
        }.toTypedArray()

        runCatching {
            boards[player.uniqueId] = SimpleScoreboard(player.receiver(), interval, title, *entries).also { it.start() }
        }.onFailure {
            FortunePillars.LOG.warn("Could not create the queue scoreboard for ${player.name}.", it)
        }
    }

    fun hide(player: Player) {
        val board = boards.remove(player.uniqueId) ?: return
        runCatching { board.stop() }
            .onFailure { FortunePillars.LOG.warn("Could not remove ${player.name}'s queue scoreboard.", it) }
    }

    // The built-in queue scoreboard, used when `queue-scoreboard.lines` is empty. Localized per
    // viewer; values are rebuilt on every update cycle.
    private fun defaultEntries(player: Player): List<ScoreboardEntry> = listOf(
        ScoreboardTemplates.CachedEntry { p ->
            mini("<gray>${p.locale().string("scoreboard.queue.players")}: <white>${QueueManager.queue.size}<gray>/<white>${Configuration.queueMinPlayers}")
        },
        ScoreboardTemplates.CachedEntry { p ->
            mini("<gray>${p.locale().string("scoreboard.queue.status")}: ").append(statusComponent(p.locale()))
        },
        ScoreboardTemplates.CachedEntry { p ->
            mini("<gray>${p.locale().string("scoreboard.queue.map")}: <white>").append(Component.text(currentMapName(p.locale())))
        },
        ScoreboardTemplates.CachedEntry { p ->
            mini("<gray>${p.locale().string("scoreboard.queue.top-mode")}: <white>${topMode(p.locale())}")
        },
        ScoreboardTemplates.CachedEntry { p ->
            mini("<gray>${p.locale().string("scoreboard.queue.top-map")}: <white>${topMap(p.locale())}")
        },
        ScoreboardTemplates.CachedEntry { p ->
            mini("<gray>${p.locale().string("scoreboard.queue.online")}: <white>${Bukkit.getOnlinePlayers().size}")
        },
    )

    // Resolves one <placeholder> of the queue scoreboard on every update. Vote placeholders fall
    // back to what would actually be used when nobody voted: the configured default mode, the
    // plain modifier, the default item time and the currently pasted arena.
    private fun resolve(key: String, player: Player, locale: Locale): String {
        refreshVoteCache()
        return when (key) {
            "players" -> QueueManager.queue.size.toString()
            "min" -> Configuration.queueMinPlayers.toString()
            "max" -> Configuration.queueMaxPlayers.toString()
            "needed" -> (Configuration.queueMinPlayers - QueueManager.queue.size).coerceAtLeast(0).toString()
            "slots-left" -> (Configuration.queueMaxPlayers - QueueManager.queue.size).coerceAtLeast(0).toString()
            "countdown" -> QueueManager.countdownSecondsLeft?.toString() ?: "-"
            "status" -> MINI_MESSAGE.serialize(statusComponent(locale))
            "map" -> currentMapName(locale)
            "mode-vote" -> topMode(locale)
            "type-vote" -> topType(locale)
            "time-vote" -> (cachedTime ?: QueueManager.timeVoteCounts()).filterKeys { it != Int.MIN_VALUE }.maxWithOrNull(compareBy({ it.value }, { it.key }))
                ?.takeIf { it.value > 0 }?.let { "${it.key}s" } ?: "${Configuration.queueDefaultTime}s"
            "map-vote" -> topMap(locale)
            "map-votes" -> (cachedMap ?: QueueManager.mapVoteCounts()).filterKeys { it != QueueManager.Vote.RANDOM }
                .maxWithOrNull(compareBy({ it.value }, { it.key }))?.takeIf { it.value > 0 }
                ?.let { (map, n) -> "${MapManager.maps[map]?.displayName ?: map} ($n)" } ?: "-"
            "votes-cast" -> QueueManager.votesCast().toString()
            "online" -> Bukkit.getOnlinePlayers().size.toString()
            "games" -> GameManager.games.size.toString()
            "player" -> player.name.escapeTags()
            "world" -> Cage.queueWorldName ?: "-"
            "clock" -> ScoreboardTemplates.clock()
            else -> ""
        }
    }

    // "Waiting for players..." while below the minimum, a live countdown once it fills - and a
    // distinct state after a failed start, so players know why nothing is happening.
    private fun statusComponent(locale: Locale): Component {
        if (QueueManager.isStartFailed)
            return MINI_MESSAGE.deserialize(locale.string("scoreboard.queue.status-failed"))
        val secondsLeft = QueueManager.countdownSecondsLeft
        return if (secondsLeft != null)
            MINI_MESSAGE.deserialize(locale.string("scoreboard.queue.status-starting", secondsLeft.toString()))
        else
            MINI_MESSAGE.deserialize(locale.string("scoreboard.queue.status-waiting"))
    }

    private fun currentMapName(locale: Locale): String =
        QueueManager.currentArenaMap()?.let { it.displayName ?: it.name } ?: locale.string("scoreboard.queue.random")

    // Deterministic vote leaders for display: highest count wins, ties resolved alphabetically
    // (unlike the actual game-start roll, which breaks ties randomly). With no votes at all they
    // fall back to what a start right now would actually use - the configured default mode, the
    // plain modifier and the currently pasted arena.
    private fun topMode(locale: Locale): String {
        val best = (cachedMode ?: QueueManager.modeVoteCounts()).filterKeys { it != QueueManager.Vote.RANDOM }
            .maxWithOrNull(compareBy({ it.value }, { it.key }))?.takeIf { it.value > 0 }?.key
            ?: return Configuration.queueMode.gameInfo.name(locale)
        return Registry.modes[best]?.gameInfo?.name(locale) ?: best
    }

    private fun topType(locale: Locale): String {
        val best = (cachedType ?: QueueManager.typeVoteCounts()).filterKeys { it != QueueManager.Vote.RANDOM }
            .maxWithOrNull(compareBy({ it.value }, { it.key }))?.takeIf { it.value > 0 }?.key
            ?: return locale.string("modifier.normal.name")
        return if (best == "multi") locale.string("modifier.multi.name") else locale.string("modifier.$best.name")
    }

    private fun topMap(locale: Locale): String {
        val best = (cachedMap ?: QueueManager.mapVoteCounts()).filterKeys { it != QueueManager.Vote.RANDOM }
            .maxWithOrNull(compareBy({ it.value }, { it.key }))?.takeIf { it.value > 0 }?.key
            ?: return currentMapName(locale)
        return MapManager.maps[best]?.let { it.displayName ?: it.name } ?: best
    }

    private fun mini(text: String): Component = MINI_MESSAGE.deserialize(text)
}
