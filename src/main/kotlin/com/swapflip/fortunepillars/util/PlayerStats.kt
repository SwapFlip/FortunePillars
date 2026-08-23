package com.swapflip.fortunepillars.util

import com.marcpg.libpg.config.PaperConfigProvider
import com.swapflip.fortunepillars.FortunePillars
import org.bukkit.Bukkit
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Per-player progression, persisted to `data/stats.yml`. Values are held in memory while a player is
// active (so a kill mid-match doesn't hit disk every time) and flushed to disk on game end, on a
// periodic timer, and on plugin shutdown - so a crash only ever loses the in-flight, not-yet-saved
// stats of players currently in a match.
object PlayerStats {
    private lateinit var dir: Path
    private lateinit var provider: PaperConfigProvider
    private val cache = ConcurrentHashMap<UUID, StatData>()

    data class StatData(
        var wins: Int = 0,
        var losses: Int = 0,
        var kills: Int = 0,
        var deaths: Int = 0,
        var gamesPlayed: Int = 0,
        var currentStreak: Int = 0,
        var bestStreak: Int = 0,
        var achievements: MutableSet<String> = mutableSetOf(),
        var cosmetics: MutableSet<String> = mutableSetOf(),
        var activeCosmetic: String = "",
    )

    fun init(plugin: FortunePillars) {
        dir = plugin.dataFolder.toPath().resolve("data")
        Files.createDirectories(dir)
        provider = PaperConfigProvider("stats.yml", dir.resolve("stats.yml")).also { it.load() }
        // Flush the in-memory cache to disk every 5 minutes so a crash loses at most a few minutes.
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { saveAll() }, 20L * 60 * 5, 20L * 60 * 5)
    }

    private fun key(uuid: UUID, name: String) = "players.$uuid.$name"

    // Returns the cached stats without touching disk. Used by hot paths (e.g. cosmetics) where an
    // uncached player simply has no active cosmetic to render.
    fun cached(uuid: UUID): StatData? = cache[uuid]

    // Loads a player's stats into the cache (once) and returns the live cached object.
    fun get(uuid: UUID): StatData {
        return cache[uuid] ?: run {
            val data = StatData(
                wins = provider.getInt(key(uuid, "wins"), 0),
                losses = provider.getInt(key(uuid, "losses"), 0),
                kills = provider.getInt(key(uuid, "kills"), 0),
                deaths = provider.getInt(key(uuid, "deaths"), 0),
                gamesPlayed = provider.getInt(key(uuid, "games"), 0),
                currentStreak = provider.getInt(key(uuid, "streak"), 0),
                bestStreak = provider.getInt(key(uuid, "best-streak"), 0),
                achievements = provider.getString(key(uuid, "achievements"), "").split(",").filter { it.isNotBlank() }.toMutableSet(),
                cosmetics = provider.getString(key(uuid, "cosmetics"), "").split(",").filter { it.isNotBlank() }.toMutableSet(),
                activeCosmetic = provider.getString(key(uuid, "active-cosmetic"), ""),
            )
            cache[uuid] = data
            data
        }
    }

    private fun set(uuid: UUID, name: String, value: Int) = provider.setInt(key(uuid, name), value)
    private fun set(uuid: UUID, name: String, value: String) = provider.setString(key(uuid, name), value)

    fun addWin(uuid: UUID) {
        val d = get(uuid)
        d.wins++
        d.currentStreak++
        if (d.currentStreak > d.bestStreak) d.bestStreak = d.currentStreak
        set(uuid, "wins", d.wins); set(uuid, "streak", d.currentStreak); set(uuid, "best-streak", d.bestStreak)
    }

    fun addLoss(uuid: UUID) {
        val d = get(uuid)
        d.losses++
        d.currentStreak = 0
        set(uuid, "losses", d.losses); set(uuid, "streak", 0)
    }

    fun addKill(uuid: UUID) {
        val d = get(uuid)
        d.kills++
        set(uuid, "kills", d.kills)
    }

    fun addDeath(uuid: UUID) {
        val d = get(uuid)
        d.deaths++
        set(uuid, "deaths", d.deaths)
    }

    fun addGame(uuid: UUID) {
        val d = get(uuid)
        d.gamesPlayed++
        set(uuid, "games", d.gamesPlayed)
    }

    fun grantAchievement(uuid: UUID, id: String): Boolean {
        val d = get(uuid)
        if (id in d.achievements) return false
        d.achievements += id
        set(uuid, "achievements", d.achievements.joinToString(","))
        return true
    }

    fun unlockCosmetic(uuid: UUID, id: String): Boolean {
        val d = get(uuid)
        if (id in d.cosmetics) return false
        d.cosmetics += id
        set(uuid, "cosmetics", d.cosmetics.joinToString(","))
        return true
    }

    fun setActiveCosmetic(uuid: UUID, id: String) {
        val d = get(uuid)
        d.activeCosmetic = id
        set(uuid, "active-cosmetic", id)
    }

    private fun statValue(d: StatData, stat: String): Int = when (stat) {
        "wins" -> d.wins
        "losses" -> d.losses
        "kills" -> d.kills
        "deaths" -> d.deaths
        "games" -> d.gamesPlayed
        "streak" -> d.bestStreak
        else -> d.wins
    }

    // Top N players for a numeric stat. Reads persisted values (so offline players count) and
    // overlays any in-memory cache entries that may not be on disk yet (e.g. just-finished match).
    fun top(stat: String, limit: Int): List<Pair<UUID, Int>> {
        val result = mutableMapOf<UUID, Int>()
        provider.configuration.getConfigurationSection("players")?.getKeys(false)?.forEach { uuidStr ->
            val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: return@forEach
            result[uuid] = provider.getInt("players.$uuidStr.${fieldFor(stat)}", 0)
        }
        cache.forEach { (uuid, d) -> result[uuid] = statValue(d, stat) }
        return result.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    private fun fieldFor(stat: String): String = when (stat) {
        "wins" -> "wins"; "losses" -> "losses"; "kills" -> "kills"; "deaths" -> "deaths"
        "games" -> "games"; "streak" -> "best-streak"; else -> "wins"
    }

    // Writes every cached player's stats to disk. Called on game end and on a timer.
    fun saveAll() {
        for ((uuid, d) in cache) {
            set(uuid, "wins", d.wins); set(uuid, "losses", d.losses); set(uuid, "kills", d.kills)
            set(uuid, "deaths", d.deaths); set(uuid, "games", d.gamesPlayed)
            set(uuid, "streak", d.currentStreak); set(uuid, "best-streak", d.bestStreak)
            set(uuid, "achievements", d.achievements.joinToString(","))
            set(uuid, "cosmetics", d.cosmetics.joinToString(","))
            set(uuid, "active-cosmetic", d.activeCosmetic)
        }
        runCatching { provider.save() }.onFailure { FortunePillars.LOG.warn("Could not save player stats.", it) }
    }
}
