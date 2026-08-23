package com.swapflip.fortunepillars.game.util

import com.marcpg.libpg.config.ExtendedEntryTypes
import com.marcpg.libpg.data.time.Time
import com.marcpg.libpg.lang.string
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.Registry
import com.swapflip.fortunepillars.game.GameCompanion
import com.swapflip.fortunepillars.generation.HorGenCompanion
import com.swapflip.fortunepillars.generation.VertGenCompanion
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.ModeConfigs
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import java.util.*

@Suppress("UnstableApiUsage")
data class GameInfo(
    val mode: GameCompanion<*>,
    val namespace: String,
    // Seconds between item drops. Read live from the mode's config file (modes/<namespace>.yml),
    // so editing the file changes the pacing of the next game without a restart.
    val itemCountdown: () -> Long = { ModeConfigs.cooldown(namespace) },
    // Total match length, parsed from the mode's `time-limit` (e.g. "10min").
    @Suppress("DEPRECATION") val timeLimit: () -> Time = { ModeConfigs.timeLimit(namespace) },

    // Theme color used for this mode's scoreboard and bossbar, from `visual.color`.
    val accentColor: () -> TextColor = { ModeConfigs.accentColor(namespace) },
    // Whether this mode shows the in-game scoreboard / bossbar (visual.show-scoreboard / -bossbar).
    val showScoreboard: () -> Boolean = { ModeConfigs.showScoreboard(namespace) },
    val showBossBar: () -> Boolean = { ModeConfigs.showBossBar(namespace) },

    // Horizontal / vertical generator selection (generator.horizontal / .vertical).
    val horGen: () -> HorGenCompanion<*> = { ModeConfigs.horGen(namespace) },
    val vertGen: () -> VertGenCompanion<*> = { ModeConfigs.vertGen(namespace) },

    // The mode's loot weighting function: maps any legal material to how likely it is to drop
    // (0 = banned). Read from modes/<namespace>.yml; when the file omits `loot`, the built-in
    // LootWeights profile for this mode is used as the fallback.
    val lootWeights: () -> (Material) -> Int = { ModeConfigs.lootWeights(namespace) },

    // Every drop cycle hands out exactly one item: a special or power-up replaces the drop, a
    // regular material is picked from the weighted pool. Multi-item drops were removed on purpose
    // - one drop is one item, always.
    val dropCount: () -> Int = { 1 },

    // Whether power-up drops are enabled for this mode (power-ups.enabled).
    val powerUpsEnabled: () -> Boolean = { ModeConfigs.powerUpEnabled(namespace) },
    // Percent chance a drop is replaced by a power-up. Per-mode override of `items.power-up-chance`.
    val powerUpChance: () -> Int = { ModeConfigs.powerUpChance(namespace) },
    // Percent chance a dropped gear item is enchanted. Per-mode override of `items.enchant-chance`.
    val enchantChance: () -> Int = { ModeConfigs.enchantChance(namespace).coerceAtMost(10) },
    // Percent chance a drop is replaced by a special item (Super Star, Fireball, Aid Platform)
    // on its own, independent of the power-up roll. Capped at 2 so specials stay rare no matter
    // what the config says - they are the strongest drops in the game.
    val specialChance: () -> Int = { ModeConfigs.specialChance(namespace).coerceAtMost(2) },
) {
    val keyStyle: () -> Style = { Style.style(accentColor(), TextDecoration.BOLD) }
    val valueStyle = Style.style(NamedTextColor.GRAY).decoration(TextDecoration.BOLD, TextDecoration.State.FALSE)

    fun name(locale: Locale) = locale.string("game.$namespace.name")
    fun description(locale: Locale) = locale.string("game.$namespace.description")
}

fun String?.fromHexToTextColor(): TextColor = (if (this == null) null else TextColor.fromHexString(this)) ?: NamedTextColor.WHITE

private fun String?.toHorGen(): HorGenCompanion<*> {
    val horGen = Registry.horizontalGenerators[this]
    if (horGen == null)
        FortunePillars.LOG.error("Configured horizontal generator '$this' does not exist!")
    return horGen ?: Registry.horizontalGenerators.values.first()
}

private fun String?.toVertGen(): VertGenCompanion<*> {
    val vertGen = Registry.verticalGenerators[this]
    if (vertGen == null)
        FortunePillars.LOG.error("Configured vertical generator '$this' does not exist!")
    return vertGen ?: Registry.verticalGenerators.values.first()
}
