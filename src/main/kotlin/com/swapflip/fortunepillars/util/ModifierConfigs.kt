package com.swapflip.fortunepillars.util

import com.marcpg.libpg.config.PaperConfigProvider
import com.swapflip.fortunepillars.FortunePillars
import java.nio.file.Files
import java.nio.file.Path

// Per-modifier configuration, loaded from `plugins/<plugin>/modifiers/<modifier>.yml`. Each file
// holds that modifier's tunables with documentation. Every getter falls back to the matching key
// under `modifiers.<modifier>` in config.yml, and then to the plugin's built-in default, so the
// files are optional: remove one and the modifier keeps working from config.yml / the defaults.
object ModifierConfigs {
    // The modifiers that ship with their own config file. Names match the vote-menu namespaces.
    val MODIFIERS = setOf(
        "lava-rises", "tnt-falls", "arrow-rain", "lightning", "moonwalk",
        "chain-swap", "ablockalypse", "lava-floor", "uhc",
        "mob-wave", "shrinking-world",
    )

    private lateinit var dir: Path
    private val providers = mutableMapOf<String, PaperConfigProvider>()

    // Loads (and, on first run, generates) every modifier file. Called from FortunePillars.onEnable
    // and re-run on the auto-reload poll so disk edits apply without a restart.
    fun init(plugin: FortunePillars) {
        dir = plugin.dataFolder.toPath().resolve("modifiers")
        Files.createDirectories(dir)
        for (modifier in MODIFIERS) loadModifier(modifier)
    }

    // Re-reads every modifier file from disk. A malformed file keeps the last good provider.
    fun reload() {
        for (modifier in MODIFIERS) runCatching { providers[modifier]?.load() }
            .onFailure { FortunePillars.LOG.warn("Failed to reload modifiers/$modifier.yml; keeping the previous config.", it) }
    }

    private fun loadModifier(modifier: String) {
        val path = dir.resolve("$modifier.yml")
        if (!path.toFile().exists()) {
            runCatching { ModifierConfigGenerator.generate(modifier, path) }
                .onFailure { FortunePillars.LOG.error("Could not generate the default modifiers/$modifier.yml.", it) }
        }
        runCatching {
            providers[modifier] = PaperConfigProvider("$modifier.yml", path).also { it.load() }
        }.onFailure { FortunePillars.LOG.error("Could not load modifiers/$modifier.yml.", it) }
    }

    private fun provider(modifier: String) = providers[modifier] ?: providers["lava-rises"]

    // Reads a key from the modifier file, falling back to config.yml's `modifiers.<modifier>.<key>`
    // and finally to the supplied default. This three-tier chain means a missing file/key never
    // breaks a modifier - it just uses the next source down.
    fun int(modifier: String, key: String, default: Int): Int =
        provider(modifier)?.getInt(key, Configuration.provider.getInt("modifiers.$modifier.$key", default))
            ?: Configuration.provider.getInt("modifiers.$modifier.$key", default)

    fun bool(modifier: String, key: String, default: Boolean): Boolean =
        provider(modifier)?.getBoolean(key, Configuration.provider.getBoolean("modifiers.$modifier.$key", default))
            ?: Configuration.provider.getBoolean("modifiers.$modifier.$key", default)

    fun double(modifier: String, key: String, default: Double): Double =
        provider(modifier)?.getDouble(key, Configuration.provider.getDouble("modifiers.$modifier.$key", default))
            ?: Configuration.provider.getDouble("modifiers.$modifier.$key", default)

    fun string(modifier: String, key: String, default: String): String =
        provider(modifier)?.getString(key, Configuration.provider.getString("modifiers.$modifier.$key", default))
            ?: Configuration.provider.getString("modifiers.$modifier.$key", default)

    // Whether a modifier file (or its config.yml section) defines any keys at all. Used by modifiers
    // that want to detect "fully custom" config vs. the built-in defaults.
    fun hasAny(modifier: String): Boolean =
        provider(modifier)?.configuration?.getKeys(false)?.isNotEmpty() == true
}
