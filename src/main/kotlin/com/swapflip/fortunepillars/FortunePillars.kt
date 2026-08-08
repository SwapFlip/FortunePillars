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
import com.swapflip.fortunepillars.util.Metrics
import com.swapflip.fortunepillars.util.FortunePillarsExpansion
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

        loadTranslations()

        Registry.load()
        Configuration.init()
        MapManager.load()
        Cage.ensureQueueWorld()?.let { LOG.info("Queue world \"${it.name}\" is ready.") }
        Metrics.start()

        if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
            runCatching { me.clip.placeholderapi.PlaceholderAPI.registerExpansion(FortunePillarsExpansion()) }
                .onSuccess { LOG.info("Registered PlaceholderAPI expansion (pp).") }
                .onFailure { LOG.warn("Could not register the PlaceholderAPI expansion.", it) }
        }

        server.pluginManager.registerEvents(GameEvents, this)
        server.pluginManager.registerEvents(PlayerEvents, this)
        server.pluginManager.registerEvents(QueueEvents, this)
        server.pluginManager.registerEvents(SpectatorEvents, this)

        Commands.register()
    }

    override fun onDisable() {
        Metrics.forceSubmit()

        GameManager.games.values.toList().forEach { it.end(Game.EndingCause.FORCE) }
        Configuration.save()

        Metrics.shutdown()
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

        val VERSION: String = "0.1"

        fun sendCommand(cmd: String) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
        }
    }
}
