package com.marcpg.pillarperil

import com.marcpg.libpg.MinecraftLibPG
import com.marcpg.libpg.lang.Translation
import com.marcpg.pillarperil.event.GameEvents
import com.marcpg.pillarperil.event.PlayerEvents
import com.marcpg.pillarperil.event.QueueEvents
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.util.GameManager
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.Metrics
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale
import java.util.Properties
import java.util.logging.Level

class PillarPeril : JavaPlugin() {
    override fun onEnable() {
        saveDefaultConfig()

        MinecraftLibPG.init(this)

        PLUGIN = this
        LOG = PluginLogger(logger)

        loadTranslations()

        Registry.load()
        Configuration.init()
        Metrics.start()

        server.pluginManager.registerEvents(GameEvents, this)
        server.pluginManager.registerEvents(PlayerEvents, this)
        server.pluginManager.registerEvents(QueueEvents, this)

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
        lateinit var PLUGIN: PillarPeril
        lateinit var LOG: PluginLogger

        val VERSION: String = "0.2.2"

        fun sendCommand(cmd: String) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
        }
    }
}
