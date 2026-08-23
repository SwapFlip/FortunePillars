package com.swapflip.fortunepillars

import com.marcpg.libpg.MinecraftLibPG
import com.marcpg.libpg.lang.Translation
import com.swapflip.fortunepillars.event.GameEvents
import com.swapflip.fortunepillars.event.PlayerEvents
import com.swapflip.fortunepillars.event.QueueEvents
import com.swapflip.fortunepillars.event.SpectatorEvents
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.util.Cage
import com.swapflip.fortunepillars.game.util.GameManager
import com.swapflip.fortunepillars.map.MapManager
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.Cosmetics
import com.swapflip.fortunepillars.util.FeatureToggle
import com.swapflip.fortunepillars.util.FortunePillarsExpansion
import com.swapflip.fortunepillars.util.Hooks
import com.swapflip.fortunepillars.util.Metrics
import com.swapflip.fortunepillars.util.ModeConfigs
import com.swapflip.fortunepillars.util.ModifierConfigs
import com.swapflip.fortunepillars.util.PlayerStats
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale
import java.util.Properties
import java.util.logging.Level

class FortunePillars : JavaPlugin() {
    override fun onEnable() {
        saveDefaultConfig()

        MinecraftLibPG.init(this)

        PLUGIN = this
        LOG = PluginLogger(logger)
        VERSION = description.version

        loadTranslations()

        Registry.load()
        Configuration.init()
        ModeConfigs.init(this)
        ModifierConfigs.init(this)
        MapManager.load()
        PlayerStats.init(this)
        Hooks.init()
        FeatureToggle.init(this)
        Cosmetics.startTask()
        Cage.ensureQueueWorld()?.let { LOG.info("Queue world \"${it.name}\" is ready.") }
        Metrics.start()

        // Watch config.yml (and the per-mode files in modes/) for external edits: admins can tune
        // the config in place and have it applied without a restart or a manual /pp-config reload.
        Bukkit.getScheduler().runTaskTimer(this, Runnable {
            Configuration.checkAutoReload()
            ModeConfigs.reload()
            ModifierConfigs.reload()
        }, 60L, 60L)

        if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
            runCatching { me.clip.placeholderapi.PlaceholderAPI.registerExpansion(FortunePillarsExpansion()) }
                .onSuccess { LOG.info("Registered PlaceholderAPI expansion (pp).") }
                .onFailure { LOG.warn("Could not register the PlaceholderAPI expansion.", it) }
        }

        server.pluginManager.registerEvents(GameEvents, this)
        server.pluginManager.registerEvents(PlayerEvents, this)
        server.pluginManager.registerEvents(QueueEvents, this)
        server.pluginManager.registerEvents(SpectatorEvents, this)

        // DeluxeHub intercepts right-clicks and offhand swaps in its active worlds, which breaks
        // special items (Fireball, Super Star, ...) and offhand usage inside the game world.
        if (server.pluginManager.isPluginEnabled("DeluxeHub")) {
            LOG.warn("DeluxeHub is loaded: it blocks item usage in the game world. Add \"${Cage.queueWorldName ?: "PillarPeril"}\" to DeluxeHub's disabled worlds (deluxehub.disabled-worlds) so players can use their items normally.")
        }

        Commands.register()
    }

    override fun onDisable() {
        Commands.unregister()
        // Flush any in-flight progression to disk before the plugin goes away.
        PlayerStats.saveAll()
        try {
            Metrics.forceSubmit()
        } catch (e: Exception) {
            LOG.warn("Failed to submit metrics during shutdown.", e)
        }

        // A single misbehaving game must never abort the plugin shutdown, or the rest of the games
        // would keep running (and their worlds/snapshots leaking) after the plugin is gone.
        GameManager.games.values.toList().forEach {
            runCatching { it.end(Game.EndingCause.FORCE) }
                .onFailure { e -> LOG.warn("Failed to stop a game during shutdown.", e) }
        }

        try {
            Metrics.shutdown()
        } catch (e: Exception) {
            LOG.warn("Failed to shut down metrics.", e)
        }
    }

    private fun loadTranslations() {
        try {
            val props = Properties()
            val stream = javaClass.classLoader.getResourceAsStream("en_US.properties") ?: return
            stream.use { props.load(it) }
            val map = props.entries.associate { it.key.toString() to it.value.toString() }.toMutableMap()
            Translation.loadMaps(mutableMapOf(Locale.US to map))
            LOG.info("Translations loaded (fallback english).")
        } catch (e: Exception) {
            LOG.warn("Could not load translations, falling back to plain english.")
        }
    }

    class PluginLogger(private val log: java.util.logging.Logger) {
        fun info(msg: String) = log.info(msg)
        fun warn(msg: String) = log.warning(msg)
        fun error(msg: String) = log.severe(msg)
        fun warn(msg: String, e: Throwable) = log.log(Level.WARNING, msg, e)
        fun error(msg: String, e: Throwable) = log.log(Level.SEVERE, msg, e)
    }

    companion object {
        lateinit var PLUGIN: FortunePillars
        lateinit var LOG: PluginLogger

        // Mirrors the version in paper-plugin.yml (read from the runtime plugin descriptor).
        lateinit var VERSION: String

        // Commands from the config can only contain these safe characters: alphanumerics, common
        // punctuation, spaces and `/` (for the leading slash or subcommand paths). Anything else
        // (semicolons, newlines, quotes that could break out of the command string) is rejected
        // so a misconfigured or malicious value can never escalate into arbitrary console input.
        private val SAFE_COMMAND = Regex("^[a-zA-Z0-9 /_\\-.:,=+%@#()\\[\\]{}]*$")

        fun sendCommand(cmd: String) {
            // Defense in depth: the values are game-generated today, but a future {player} or
            // {world} placeholder could carry text that breaks out of the command string.
            if (!SAFE_COMMAND.matches(cmd)) {
                LOG.warn("Blocked a console command containing unsafe characters: ${cmd.replace(Regex("\\s+"), " ").take(120)}")
                return
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
        }
    }
}
