package com.swapflip.fortunepillars.util

import com.marcpg.libpg.config.PaperConfigProvider
import com.swapflip.fortunepillars.FortunePillars
import java.nio.file.Files
import java.nio.file.Path

// Master on/off switch for the plugin, controlled by admins via /pp on and /pp off. Stored in its
// own file (data/state.yml) rather than config.yml, so it survives config wipes and restarts: it
// exists purely so an admin can disable queuing (e.g. to test the plugin) while still letting
// themselves (OP) queue and play.
object FeatureToggle {
    private lateinit var dir: Path
    private lateinit var provider: PaperConfigProvider

    // True when the plugin is enabled (the default). Read on every queue attempt.
    var enabled: Boolean = true
        private set

    fun init(plugin: FortunePillars) {
        dir = plugin.dataFolder.toPath().resolve("data")
        Files.createDirectories(dir)
        provider = PaperConfigProvider("state.yml", dir.resolve("state.yml")).also { it.load() }
        enabled = provider.getBoolean("enabled", true)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        provider.setBoolean("enabled", value)
        runCatching { provider.save() }.onFailure { FortunePillars.LOG.warn("Could not persist the plugin on/off state.", it) }
    }
}
