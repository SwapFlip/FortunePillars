package com.swapflip.fortunepillars.util

import com.marcpg.libpg.config.PaperConfigProvider
import com.marcpg.libpg.data.time.Time
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.Registry
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.mode.LootWeights
import com.swapflip.fortunepillars.generation.HorGenCompanion
import com.swapflip.fortunepillars.generation.VertGenCompanion
import com.swapflip.fortunepillars.player.SpecialItems
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionType
import java.nio.file.Files
import java.nio.file.Path

// Per-mode configuration, loaded from `plugins/<plugin>/modes/<mode>.yml`. Each mode file holds
// every tunable for that game mode: behavior, loot weights (with the full allowed/banned item
// lists), per-item-family enchant chances, power-up pool, special items and potion pools.
//
// Every accessor falls back to the plugin's built-in defaults (LootWeights, SpecialItems, the
// `modes.<mode>` block in config.yml) when a key - or the whole file - is missing, so existing
// installs keep working untouched and admins only document what they want to change.
object ModeConfigs {
    // The four modes that ship with their own config file. Unknown namespaces fall back to normal.
    val MODES = setOf("normal", "blocky", "action", "op")

    private lateinit var modesDir: Path
    private val providers = mutableMapOf<String, PaperConfigProvider>()

    private data class ModeDefaults(
        val cooldown: Long,
        val timeLimit: String,
        val color: String,
        val showScoreboard: Boolean,
        val showBossBar: Boolean,
        val horizontal: String,
        val vertical: String,
    )

    private val defaultValues = mapOf(
        "normal" to ModeDefaults(10L, "10min", "#FFFFFF", true, true, "circular", "pillar"),
        "blocky" to ModeDefaults(10L, "10min", "#FFAA00", true, false, "circular", "pillar"),
        "action" to ModeDefaults(3L, "10min", "#FF5555", true, true, "circular", "pillar"),
        "op" to ModeDefaults(10L, "10min", "#AA00FF", true, true, "circular", "pillar"),
    )

    // Loads (and, on first run, generates) every mode file. Called from FortunePillars.onEnable and
    // re-run on config auto-reload so disk edits apply without a restart.
    fun init(plugin: FortunePillars) {
        modesDir = plugin.dataFolder.toPath().resolve("modes")
        Files.createDirectories(modesDir)
        for (mode in MODES) loadMode(mode)
    }

    // Re-reads every mode file from disk. Cheap enough to run on the auto-reload poll; a malformed
    // file keeps the last good provider for that mode instead of breaking the whole game.
    fun reload() {
        for (mode in MODES) runCatching { providers[mode]?.load() }
            .onFailure { FortunePillars.LOG.warn("Failed to reload modes/$mode.yml; keeping the previous config.", it) }
    }

    // Generates the documented default file for a mode if it does not exist yet, then loads it.
    private fun loadMode(mode: String) {
        val path = modesDir.resolve("$mode.yml")
        if (!path.toFile().exists()) {
            runCatching { ModeConfigGenerator.generate(mode, path) }
                .onFailure { FortunePillars.LOG.error("Could not generate the default modes/$mode.yml.", it) }
        }
        runCatching {
            providers[mode] = PaperConfigProvider("$mode.yml", path).also { it.load() }
        }.onFailure { FortunePillars.LOG.error("Could not load modes/$mode.yml.", it) }
    }

    private fun provider(mode: String) = providers[mode] ?: providers["normal"]

    private fun defaults(mode: String) = defaultValues[mode] ?: defaultValues.getValue("normal")

    // Reads a nested section as a raw [key -> value] map, or null when the path is absent.
    private fun section(mode: String, path: String): Map<String, Any>? =
        provider(mode)?.configuration?.getConfigurationSection(path)?.getValues(false)

    private fun string(mode: String, path: String, default: String) =
        provider(mode)?.getString(path, default) ?: default

    private fun int(mode: String, path: String, default: Int) =
        provider(mode)?.getInt(path, default) ?: default

    private fun bool(mode: String, path: String, default: Boolean) =
        provider(mode)?.getBoolean(path, default) ?: default

    private fun legacyString(mode: String, path: String, default: String): String =
        Configuration.provider.getString("modes.$mode.$path", default)

    private fun legacyInt(mode: String, path: String, default: Int): Int =
        Configuration.provider.getInt("modes.$mode.$path", default)

    private fun legacyLong(mode: String, path: String, default: Long): Long =
        Configuration.provider.getLong("modes.$mode.$path", default)

    private fun legacyBool(mode: String, path: String, default: Boolean): Boolean =
        Configuration.provider.getBoolean("modes.$mode.$path", default)

    // ── Mode behavior ────────────────────────────────────────────────────────────────────────

    // Seconds between item drops. Editable at runtime: the next game's countdown uses the new value.
    fun cooldown(mode: String): Long =
        provider(mode)?.getLong("cooldown", legacyLong(mode, "cooldown", defaults(mode).cooldown))
            ?: legacyLong(mode, "cooldown", defaults(mode).cooldown)

    // Total length of a match. Stored as a time string like "10min"; parsed through the same
    // Time helper the rest of the plugin uses so "10m" / "600s" work too.
    fun timeLimit(mode: String): Time =
        com.marcpg.libpg.config.ExtendedEntryTypes.timeString.convert(string(mode, "time-limit", legacyString(mode, "time-limit", defaults(mode).timeLimit)))
            ?: Time(10, Time.Unit.MINUTES)

    // Theme color used in scoreboards and titles. Falls back to white when the hex is invalid.
    fun accentColor(mode: String): TextColor =
        TextColor.fromHexString(string(mode, "visual.color", legacyString(mode, "visual.color", defaults(mode).color))) ?: NamedTextColor.WHITE

    // Whether the in-game sidebar shows for this mode.
    fun showScoreboard(mode: String): Boolean =
        bool(mode, "visual.show-scoreboard", legacyBool(mode, "visual.show-scoreboard", defaults(mode).showScoreboard))

    // Whether the item-countdown bossbar shows for this mode.
    fun showBossBar(mode: String): Boolean =
        bool(mode, "visual.show-bossbar", legacyBool(mode, "visual.show-bossbar", defaults(mode).showBossBar))

    // Horizontal layout of the pillars/blocks: "circular" or "random".
    fun horGen(mode: String): HorGenCompanion<*> {
        val configured = string(mode, "generator.horizontal", legacyString(mode, "generator.horizontal", defaults(mode).horizontal))
        val gen = Registry.horizontalGenerators[configured]
        if (gen == null) FortunePillars.LOG.error("Configured horizontal generator '$configured' does not exist!")
        return gen ?: Registry.horizontalGenerators.values.first()
    }

    // Vertical layout of the platforms: "pillar" or "block".
    fun vertGen(mode: String): VertGenCompanion<*> {
        val configured = string(mode, "generator.vertical", legacyString(mode, "generator.vertical", defaults(mode).vertical))
        val gen = Registry.verticalGenerators[configured]
        if (gen == null) FortunePillars.LOG.error("Configured vertical generator '$configured' does not exist!")
        return gen ?: Registry.verticalGenerators.values.first()
    }

    // Items this mode never drops (on top of the global `items.blacklist`). Merged into the loot
    // weights below and also applied directly in Game.buildItems.
    fun blacklist(mode: String): Set<Material> =
        provider(mode)?.getStringList("blacklist")?.mapNotNull { Material.matchMaterial(it) }?.toSet() ?: emptySet()

    // ── Loot weighting ──────────────────────────────────────────────────────────────────────

    // The weighting function for a mode: maps any material to its drop weight (0 = banned). Reads
    // `loot.weights` (category fallbacks), `loot.allowed` (per-item overrides) and `loot.banned`;
    // falls back to the built-in LootWeights profile when the whole `loot` section is absent.
    fun lootWeights(mode: String): (Material) -> Int {
        val cfg = section(mode, "loot") ?: return legacyLootWeights(mode)
        val profile = LootWeights.PROFILES[mode]

        val catWeights = LootWeights.Kind.entries.associateWith { kind ->
            (section(mode, "loot.weights")?.get(kind.name.lowercase()) as? Int)
                ?: profile?.categoryWeights?.get(kind) ?: 1
        }
        val allowed = section(mode, "loot.allowed")
            ?.mapNotNull { (name, value) -> Material.matchMaterial(name)?.let { it to ((value as? Int) ?: 0) } }
            ?.toMap() ?: emptyMap()
        val hasExplicitBanned = provider(mode)?.configuration?.contains("loot.banned") == true
        val fileBanned = provider(mode)?.getStringList("loot.banned")?.mapNotNull { Material.matchMaterial(it) }?.toSet() ?: emptySet()
        val banned = if (hasExplicitBanned) fileBanned else (profile?.banned ?: emptySet())

        return { material ->
            if (material in banned) 0
            else allowed[material] ?: catWeights.getValue(LootWeights.kindOf(material))
        }
    }

    private fun legacyLootWeights(mode: String): (Material) -> Int {
        if (mode == "normal" && Configuration.itemsPool.isNotEmpty()) {
            val weights = Configuration.itemsPool.mapNotNull { (name, weight) ->
                Material.matchMaterial(name)?.let { it to weight }
            }.toMap()
            return { material -> weights[material] ?: 0 }
        }
        return LootWeights.of(mode)
    }

    // ── Enchantments ────────────────────────────────────────────────────────────────────────

    // Per-mode override of the global `items.enchant-chance`. Capped low in code so a high value
    // can never flood the match with enchanted gear.
    fun enchantChance(mode: String): Int {
        val value = int(mode, "enchant.chance", Configuration.enchantChance)
        return if (value < 0) Configuration.enchantChance else value
    }

    // Per-item-family enchant settings: the chance a dropped item of that family is enchanted, and
    // which enchantments (with their max level) can roll. Falls back to the current hard-coded
    // pools in SpecialItems.maybeEnchant for any family the file leaves out.
    fun enchantFamilies(mode: String): Map<String, FamilyEnchantConfig> {
        val famSection = section(mode, "enchant.families") ?: return emptyMap()
        return famSection.mapNotNull { (family, _) ->
            val chance = int(mode, "enchant.families.$family.chance", 10).coerceIn(0, 10)
            val enchants = section(mode, "enchant.families.$family.enchants")
                ?.mapNotNull { (name, lvl) ->
                    val maxLevel = (lvl as? Int) ?: 2
                    if (maxLevel <= 0) null else configEnchantment(name)?.let { it to maxLevel }
                }?.toMap() ?: emptyMap()
            family to FamilyEnchantConfig(chance, enchants)
        }.toMap()
    }

    data class FamilyEnchantConfig(val chance: Int, val enchants: Map<Enchantment, Int>)

    // ── Power-ups ───────────────────────────────────────────────────────────────────────────

    // Whether power-up drops are enabled for this mode.
    fun powerUpEnabled(mode: String): Boolean = bool(mode, "power-ups.enabled", Configuration.powerUpsEnabled)

    // Percent chance a drop is replaced by a power-up instead of a regular item. Falls back to the
    // global `items.power-up-chance`.
    fun powerUpChance(mode: String): Int {
        val value = int(mode, "power-ups.chance", Configuration.powerUpChance)
        return if (value < 0) Configuration.powerUpChance else value
    }

    // The plain survival-item part of the power-up pool: material -> weight. When empty,
    // SpecialItems.randomPowerUp falls back to its built-in default pool.
    fun powerUpItems(mode: String): Map<Material, Int> =
        section(mode, "power-ups.items")
            ?.mapNotNull { (name, w) -> Material.matchMaterial(name)?.let { it to ((w as? Int) ?: 1) } }
            ?.filter { it.second > 0 }
            ?.toMap() ?: emptyMap()

    // The potion part of the power-up pool: effect name -> weight. Mapped to PotionType via the
    // version-compatible resolver; unknown names are skipped.
    fun powerUpPotions(mode: String): Map<PotionType, Int> =
        section(mode, "power-ups.potions")
            ?.mapNotNull { (name, w) -> configPotionType(name)?.let { it to ((w as? Int) ?: 1) } }
            ?.filter { it.second > 0 }
            ?.toMap() ?: emptyMap()

    // ── Special items ──────────────────────────────────────────────────────────────────────

    // Percent chance a drop is a special item on its own. Capped to 2 in GameInfo so specials stay
    // the rarest drops no matter what the config says.
    fun specialChance(mode: String): Int {
        val value = int(mode, "specials.chance", Configuration.specialChance)
        return if (value < 0) Configuration.specialChance else value
    }

    // The special items this mode can roll, derived from the four built-in specials. The config
    // controls each one's presence (weight, 0 = disabled) and can override its material/gradient;
    // the display name and usage lore stay tied to the locale. When the `specials.items` section is
    // empty, SpecialItems falls back to all four built-ins at weight 1.
    fun specialItems(mode: String): Map<String, SpecialItems.ConfigSpecial> {
        val section = section(mode, "specials.items") ?: return emptyMap()
        return SpecialItems.SPECIALS.mapNotNull { base ->
            val weight = int(mode, "specials.items.${base.id}.weight", 1).coerceAtLeast(0)
            if (weight == 0) return@mapNotNull null
            val material = Material.matchMaterial(string(mode, "specials.items.${base.id}.material", base.material.name)) ?: base.material
            val gradient = string(mode, "specials.items.${base.id}.gradient", base.gradient)
            base.id to SpecialItems.ConfigSpecial(base.id, material, gradient, base.nameKey, base.usageKey, weight)
        }.toMap()
    }

    // ── Potion / stew pools ────────────────────────────────────────────────────────────────

    // The random potion-effect pool. When empty, SpecialItems falls back to its built-in list.
    fun potionEffects(mode: String): List<PotionType> =
        provider(mode)?.getStringList("potions.effects")?.mapNotNull { configPotionType(it) } ?: emptyList()

    // The random suspicious-stew effect pool. When empty, SpecialItems falls back to its built-in list.
    fun stewEffects(mode: String): List<PotionEffectType> =
        provider(mode)?.getStringList("potions.stew-effects")?.mapNotNull { configPotionEffectType(it) } ?: emptyList()

    // Every material the mode is barred from dropping: the global `items.blacklist`, the per-mode
    // `modes.<mode>.blacklist` (legacy config.yml), this file's `blacklist`/`loot.banned`, and the
    // always-unobtainable technical blocks. Used by Game.buildItems so the same veto list applies
    // whether loot comes from the file or the built-in profile.
    fun effectiveBlacklist(mode: String): Set<Material> =
        (Configuration.itemsBlacklist + Configuration.modeBlacklist(mode) + blacklist(mode) + Game.UNOBTAINABLE_ITEMS).toSet()

    private fun normalizeConfigName(name: String): String =
        name.lowercase().removePrefix("minecraft:").replace('-', '_').replace(' ', '_')

    private fun configEnchantment(name: String): Enchantment? {
        val normalized = normalizeConfigName(name)
        val candidates = enchantAliases[normalized] ?: listOf(normalized.uppercase())
        return enchantment(candidates.first(), *candidates.drop(1).toTypedArray())
    }

    private fun configPotionType(name: String): PotionType? {
        val normalized = normalizeConfigName(name)
        val candidates = potionAliases[normalized] ?: listOf(normalized.uppercase())
        return potionType(candidates.first(), *candidates.drop(1).toTypedArray())
    }

    private fun configPotionEffectType(name: String): PotionEffectType? {
        val normalized = normalizeConfigName(name)
        val candidates = effectAliases[normalized] ?: listOf(normalized.uppercase())
        return potionEffectType(candidates.first(), *candidates.drop(1).toTypedArray())
    }

    private val enchantAliases = mapOf(
        "sharpness" to listOf("DAMAGE_ALL", "SHARPNESS"),
        "efficiency" to listOf("DIG_SPEED", "EFFICIENCY"),
        "fortune" to listOf("LOOT_BONUS_BLOCKS", "FORTUNE"),
        "protection" to listOf("PROTECTION_ENVIRONMENTAL", "PROTECTION"),
        "fire_protection" to listOf("PROTECTION_FIRE", "FIRE_PROTECTION"),
        "projectile_protection" to listOf("PROTECTION_PROJECTILE", "PROJECTILE_PROTECTION"),
        "feather_falling" to listOf("PROTECTION_FALL", "FEATHER_FALLING"),
        "respiration" to listOf("OXYGEN", "RESPIRATION"),
        "aqua_affinity" to listOf("WATER_WORKER", "AQUA_AFFINITY"),
        "power" to listOf("ARROW_DAMAGE", "POWER"),
        "punch" to listOf("ARROW_KNOCKBACK", "PUNCH"),
        "flame" to listOf("ARROW_FIRE", "FLAME"),
        "infinity" to listOf("ARROW_INFINITE", "INFINITY"),
        "quick_charge" to listOf("QUICK_CHARGE"),
        "fire_aspect" to listOf("FIRE_ASPECT"),
        "knockback" to listOf("KNOCKBACK"),
        "looting" to listOf("LOOTING"),
        "silk_touch" to listOf("SILK_TOUCH"),
        "depth_strider" to listOf("DEPTH_STRIDER"),
        "multishot" to listOf("MULTISHOT"),
        "piercing" to listOf("PIERCING"),
        "impaling" to listOf("IMPALING"),
        "loyalty" to listOf("LOYALTY"),
        "riptide" to listOf("RIPTIDE"),
    )

    private val potionAliases = mapOf(
        "speed" to listOf("SPEED", "SWIFTNESS"),
        "leaping" to listOf("LEAPING", "JUMP_BOOST"),
        "jump_boost" to listOf("JUMP_BOOST", "LEAPING"),
        "healing" to listOf("HEALING", "INSTANT_HEAL"),
        "instant_heal" to listOf("INSTANT_HEAL", "HEALING"),
        "harming" to listOf("HARMING", "INSTANT_DAMAGE"),
        "instant_damage" to listOf("INSTANT_DAMAGE", "HARMING"),
        "strength" to listOf("STRENGTH"),
        "poison" to listOf("POISON"),
        "slowness" to listOf("SLOWNESS"),
        "weakness" to listOf("WEAKNESS"),
        "regeneration" to listOf("REGENERATION"),
        "night_vision" to listOf("NIGHT_VISION"),
        "fire_resistance" to listOf("FIRE_RESISTANCE"),
        "water_breathing" to listOf("WATER_BREATHING"),
    )

    private val effectAliases = potionAliases + mapOf(
        "jump" to listOf("JUMP", "JUMP_BOOST"),
        "nausea" to listOf("NAUSEA", "CONFUSION"),
        "hunger" to listOf("HUNGER"),
        "blindness" to listOf("BLINDNESS"),
        "levitation" to listOf("LEVITATION"),
        "luck" to listOf("LUCK"),
        "saturation" to listOf("SATURATION"),
    )
}
