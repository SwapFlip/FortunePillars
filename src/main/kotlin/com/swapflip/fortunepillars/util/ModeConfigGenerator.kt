package com.swapflip.fortunepillars.util

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.mode.LootWeights
import com.swapflip.fortunepillars.player.SpecialItems
import org.bukkit.Material
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionType
import java.nio.file.Files
import java.nio.file.Path

// Writes the documented default `modes/<mode>.yml` file on first run. The generated file mirrors
// the plugin's built-in defaults exactly (weights from LootWeights, bans from the mode profile,
// power-up/special/potion pools from SpecialItems) but surfaces every value as an editable key with
// a comment. Items the server version does not have are skipped automatically, so the file always
// matches the running game's legal-item set.
object ModeConfigGenerator {
    // Default power-up pool (survival items + potions), mirrored from SpecialItems.legacyPowerUp.
    private val POWER_UP_ITEMS = mapOf(
        "golden_apple" to 2,
        "ender_pearl" to 2,
        "flint_and_steel" to 1,
        "snowball" to 1,
    )
    private val POWER_UP_POTIONS = mapOf(
        "speed" to 1,
        "strength" to 1,
        "leaping" to 1,
    )

    // Default per-family enchant settings: chance a dropped item of that family is enchanted, and
    // which enchantments (with max level) can roll. Mirrors SpecialItems.legacyEnchantPool.
    private val FAMILY_DEFAULTS = mapOf(
        "sword" to (15 to mapOf("sharpness" to 2, "fire_aspect" to 1, "knockback" to 1, "looting" to 1)),
        "axe" to (10 to mapOf("sharpness" to 2, "efficiency" to 2)),
        "pickaxe" to (10 to mapOf("efficiency" to 2, "fortune" to 1, "silk_touch" to 1)),
        "helmet" to (10 to mapOf("protection" to 2, "respiration" to 1, "aqua_affinity" to 1)),
        "chestplate" to (10 to mapOf("protection" to 2, "fire_protection" to 1, "projectile_protection" to 1)),
        "leggings" to (10 to mapOf("protection" to 2, "fire_protection" to 1, "projectile_protection" to 1)),
        "boots" to (10 to mapOf("protection" to 2, "feather_falling" to 1, "depth_strider" to 1)),
        "bow" to (10 to mapOf("power" to 2, "punch" to 1, "flame" to 1, "infinity" to 1)),
        "crossbow" to (10 to mapOf("multishot" to 1, "piercing" to 2, "quick_charge" to 2)),
        "trident" to (10 to mapOf("impaling" to 2, "loyalty" to 1, "riptide" to 1)),
    )

    private val FAMILY_DOCS = mapOf(
        "sword" to "Swords (every *_sword). Applies sword-only enchantments.",
        "axe" to "Axes (every *_axe). Applies axe-only enchantments.",
        "pickaxe" to "Pickaxes, shovels and hoes share one family (all dig tools).",
        "helmet" to "Helmets (every *_helmet). Applies head-slot protections.",
        "chestplate" to "Chestplates (every *_chestplate). Applies torso-slot protections.",
        "leggings" to "Leggings (every *_leggings). Applies leg-slot protections.",
        "boots" to "Boots (every *_boots). Applies foot-slot protections.",
        "bow" to "Bows. Applies bow-only enchantments.",
        "crossbow" to "Crossbows. Applies crossbow-only enchantments.",
        "trident" to "Tridents. Applies trident-only enchantments.",
    )

    // The default potion-effect pool (effect name -> PotionType), mirrored from SpecialItems.
    private val POTION_EFFECTS = listOf(
        "speed", "strength", "leaping", "healing", "harming", "poison",
        "slowness", "weakness", "regeneration", "night_vision", "fire_resistance", "water_breathing",
    )

    // The default suspicious-stew effect pool (effect name -> PotionEffectType), mirrored from SpecialItems.
    private val STEW_EFFECTS = listOf(
        "speed", "jump", "regeneration", "night_vision", "fire_resistance", "blindness",
        "weakness", "slowness", "poison", "hunger", "nausea", "levitation", "luck", "saturation",
    )

    private data class ModeDefaults(
        val cooldown: Long,
        val timeLimit: String,
        val color: String,
        val showScoreboard: Boolean,
        val showBossBar: Boolean,
        val horizontal: String,
        val vertical: String,
    )

    private val MODE_DEFAULTS = mapOf(
        "normal" to ModeDefaults(10L, "10min", "#FFFFFF", true, true, "circular", "pillar"),
        "blocky" to ModeDefaults(10L, "10min", "#FFAA00", true, false, "circular", "pillar"),
        "action" to ModeDefaults(3L, "10min", "#FF5555", true, true, "circular", "pillar"),
        "op" to ModeDefaults(10L, "10min", "#AA00FF", true, true, "circular", "pillar"),
    )

    private fun defaults(mode: String) = MODE_DEFAULTS[mode] ?: MODE_DEFAULTS.getValue("normal")

    fun generate(mode: String, path: Path) {
        val profile = LootWeights.PROFILES[mode] ?: LootWeights.PROFILES["normal"]!!
        val defaults = defaults(mode)
        val cooldown = Configuration.provider.getLong("modes.$mode.cooldown", defaults.cooldown)
        val timeLimit = Configuration.provider.getString("modes.$mode.time-limit", defaults.timeLimit)
        val color = Configuration.provider.getString("modes.$mode.visual.color", defaults.color)
        val showScoreboard = Configuration.provider.getBoolean("modes.$mode.visual.show-scoreboard", defaults.showScoreboard)
        val showBossBar = Configuration.provider.getBoolean("modes.$mode.visual.show-bossbar", defaults.showBossBar)
        val horizontal = Configuration.provider.getString("modes.$mode.generator.horizontal", defaults.horizontal)
        val vertical = Configuration.provider.getString("modes.$mode.generator.vertical", defaults.vertical)
        val sb = StringBuilder()

        sb.appendLine("# ############################################################################### #")
        sb.appendLine("# #                     Fortune Pillars - $mode mode config                     # #")
        sb.appendLine("# ############################################################################### #")
        sb.appendLine("#")
        sb.appendLine("# Every value below is editable and picked up live (no restart needed) when")
        sb.appendLine("# `config.auto-reload` is enabled, or by running /pp-config reload. Anything you")
        sb.appendLine("# delete or leave out falls back to the plugin's built-in default for this mode,")
        sb.appendLine("# so you only document what you want to change.")
        sb.appendLine("#")
        sb.appendLine("# Percentages shown next to loot items are computed from the default weights and")
        sb.appendLine("# are only a guide: change a weight and the real percentage shifts accordingly.")
        sb.appendLine("#")

        // ── Mode behavior ──
        sb.appendLine("# ── MODE BEHAVIOR ───────────────────────────────────────────────────────────")
        sb.appendLine("# Seconds between item drops. Lower = faster, more chaotic fights.")
        sb.appendLine("# Edit anytime; the next game's countdown uses the new value.")
        sb.appendLine("cooldown: $cooldown")
        sb.appendLine("")
        sb.appendLine("# Total length of a match, as a single time unit (e.g. 10min, 600s, 5m).")
        sb.appendLine("# The game ends (or forces a draw/winner) when this runs out.")
        sb.appendLine("time-limit: $timeLimit")
        sb.appendLine("")
        sb.appendLine("visual:")
        sb.appendLine("  # Theme color (hex) used in the scoreboard and bossbar for this mode.")
        sb.appendLine("  color: \"$color\"")
        sb.appendLine("  # Whether the in-game sidebar scoreboard shows for this mode.")
        sb.appendLine("  show-scoreboard: $showScoreboard")
        sb.appendLine("  # Whether the item-countdown bossbar shows for this mode.")
        sb.appendLine("  show-bossbar: $showBossBar")
        sb.appendLine("")
        sb.appendLine("generator:")
        sb.appendLine("  # Horizontal layout of the pillars/blocks: \"circular\" (ring) or \"random\".")
        sb.appendLine("  horizontal: $horizontal")
        sb.appendLine("  # Vertical platform type: \"pillar\" (tall column) or \"block\" (flat pad).")
        sb.appendLine("  vertical: $vertical")
        sb.appendLine("")

        // ── Per-mode blacklist ──
        sb.appendLine("# Items this mode never drops, on top of the global `items.blacklist`.")
        sb.appendLine("# A simpler veto than `loot.banned` below: just list material names here.")
        sb.appendLine("blacklist: []")
        sb.appendLine("")

        // ── Loot weighting ──
        sb.appendLine("# ── LOOT WEIGHTING ──────────────────────────────────────────────────────────")
        sb.appendLine("# How likely each item is to drop. A higher weight = more common. A weight of 0")
        sb.appendLine("# bans the item for this mode (also see `banned` below).")
        sb.appendLine("loot:")
        sb.appendLine("  # Category weights: the default weight for any item of that kind unless it has")
        sb.appendLine("  # a specific override in `allowed` below. A weight of 0 means that whole kind")
        sb.appendLine("  # never drops (e.g. blocky mode sets `egg` to 0). The actual drop is chosen by")
        sb.appendLine("  # repeating each item `weight` times into the pool, so weights are relative,")
        sb.appendLine("  # not absolute percentages.")
        sb.appendLine("  weights:")
        for (kind in LootWeights.Kind.entries) {
            val w = profile.categoryWeights[kind] ?: 1
            sb.appendLine("    ${kind.name.lowercase()}: $w   # weight for every $kind item by default")
        }
        sb.appendLine("")

        // Allowed items enumeration
        val globalBlacklist = Configuration.itemsBlacklist.toSet()
        val modeBlacklistCfg = Configuration.modeBlacklist(mode)
        val legal = Material.values().filter {
            it.isItem && it !in Game.UNOBTAINABLE_ITEMS && !Game.isLootJunk(it) && it !in globalBlacklist && it !in modeBlacklistCfg
        }
        val allowed = legal.mapNotNull { m -> profile.weight(m).let { w -> if (w > 0) m to w else null } }
        val totalWeight = allowed.sumOf { it.second }.coerceAtLeast(1)

        sb.appendLine("  # ALL ALLOWED ITEMS for this mode, each with its drop weight and an approximate")
        sb.appendLine("  # chance-to-appear (weight / total-weight). Edit a weight to retune; set it to 0")
        sb.appendLine("  # to ban that specific item. Adding a new material here also whitelists it.")
        sb.appendLine("  # Quirks to know when tuning:")
        sb.appendLine("  #  - Snowballs always drop in bundles of 4, regardless of weight.")
        sb.appendLine("  #  - Crossbows arrive pre-loaded with an arrow; potions & suspicious stew get a")
        sb.appendLine("  #    random effect (see `potions` below).")
        sb.appendLine("  #  - Enchanted gear is capped at the family's max level (see `enchant`), so a")
        sb.appendLine("  #    dropped sword can never outclass a moderately-enchanted crafted one.")
        sb.appendLine("  #  - Operator-only items, boss/peaceful eggs, horse armor and technical blocks")
        sb.appendLine("  #    are excluded globally and never appear, even with a high weight.")
        sb.appendLine("  allowed:")
        allowed.sortedBy { it.first.name }.forEach { (material, weight) ->
            val pct = (weight * 10000.0 / totalWeight).toInt() / 100.0
            sb.appendLine("    ${material.name.lowercase()}: $weight   # ≈ $pct%")
        }
        sb.appendLine("")

        // Banned items enumeration
        sb.appendLine("  # ALL BANNED ITEMS for this mode (never drop). Each line explains why it is")
        sb.appendLine("  # banned. These are blocked on top of `blacklist` above and the global")
        sb.appendLine("  # `items.blacklist`.")
        sb.appendLine("  banned:")
        val banned = collectBanned(mode, globalBlacklist, modeBlacklistCfg)
        banned.sortedBy { it.first.name }.forEach { (material, reason) ->
            sb.appendLine("    - ${material.name.lowercase()}   # $reason")
        }
        sb.appendLine("")

        // ── Enchantments ──
        sb.appendLine("# ── ENCHANTMENTS ────────────────────────────────────────────────────────────")
        sb.appendLine("# Chance (percent) a dropped gear item is enchanted, and which enchantments can")
        sb.appendLine("# roll per item family. `chance` here overrides the global `items.enchant-chance`.")
        sb.appendLine("# Quirks:")
        sb.appendLine("#  - Each family rolls its OWN chance; a sword and a bow are enchanted")
        sb.appendLine("#    independently of each other.")
        sb.appendLine("#  - The chosen enchant's level is random between 1 and the listed max level,")
        sb.appendLine("#    so a higher max just raises the ceiling, it does not guarantee it.")
        sb.appendLine("#  - Enchantments are only applied if they can actually enchant that item; an")
        sb.appendLine("#    invalid combo (e.g. a bow enchant on a sword) is skipped safely.")
        sb.appendLine("#  - The global `items.enchant-chance` is used as a fallback for any family you")
        sb.appendLine("#    omit here, and is itself capped low in code so it can't flood the match.")
        sb.appendLine("enchant:")
        sb.appendLine("  chance: ${Configuration.enchantChance}   # per-mode override of the global enchant chance")
        sb.appendLine("  families:")
        for ((family, pair) in FAMILY_DEFAULTS) {
            val (chance, enchants) = pair
            sb.appendLine("    # ${FAMILY_DOCS[family] ?: family}")
            sb.appendLine("    $family:")
            sb.appendLine("      chance: $chance   # percent a dropped $family is enchanted")
            sb.appendLine("      enchants:")
            for ((enchant, max) in enchants) {
                sb.appendLine("        $enchant: $max   # enchant name -> max level it can roll at")
            }
        }
        sb.appendLine("")

        // ── Power-ups ──
        sb.appendLine("# ── POWER-UPS ──────────────────────────────────────────────────────────────")
        sb.appendLine("# Useful survival items that can replace a regular drop. `chance` overrides the")
        sb.appendLine("# global `items.power-up-chance`. Weights below set how common each one is.")
        sb.appendLine("# Quirks:")
        sb.appendLine("#  - Power-ups only roll when `enabled` is true AND a separate `specials.chance`")
        sb.appendLine("#    roll did not already claim the drop.")
        sb.appendLine("#  - Snowballs drop as a bundle of 4; flint & steel and golden apples are single.")
        sb.appendLine("#  - Potion effects are picked at random from `potions.effects`; Invisibility is")
        sb.appendLine("#    deliberately absent so players can always be fought.")
        sb.appendLine("#  - While the lava rises, snowballs are pulled from the roll (knocking players")
        sb.appendLine("#    into the flood is too brutal).")
        sb.appendLine("power-ups:")
        sb.appendLine("  enabled: ${Configuration.powerUpsEnabled}")
        sb.appendLine("  chance: ${Configuration.powerUpChance}   # percent a drop becomes a power-up")
        sb.appendLine("  items:   # plain survival items: material -> weight")
        for ((name, w) in POWER_UP_ITEMS) sb.appendLine("    $name: $w")
        sb.appendLine("  potions: # potion effects that can roll: effect -> weight")
        for ((name, w) in POWER_UP_POTIONS) sb.appendLine("    $name: $w")
        sb.appendLine("")

        // ── Specials ──
        sb.appendLine("# ── SPECIAL ITEMS ───────────────────────────────────────────────────────────")
        sb.appendLine("# The rare, named special items. `chance` overrides the global")
        sb.appendLine("# `items.special-chance` (capped at 2 in code so specials stay the rarest drops).")
        sb.appendLine("# Set a special's weight to 0 to disable it, or change its material/gradient.")
        sb.appendLine("# Quirks:")
        sb.appendLine("#  - A special REPLACES the whole drop; it never appears alongside a normal item.")
        sb.appendLine("#  - The Super Star, Fireball and Aid Platform get a colored name + usage lore")
        sb.appendLine("#    and a glint so they never blend into the loot.")
        sb.appendLine("#  - While the lava rises, the Fireball is pulled from the roll (same reason as")
        sb.appendLine("#    snowballs above).")
        sb.appendLine("#  - Deleting a special's entry does NOT disable it (the default is used); set")
        sb.appendLine("#    its weight to 0 to turn it off.")
        sb.appendLine("specials:")
        sb.appendLine("  chance: ${Configuration.specialChance}   # percent a drop becomes a special")
        sb.appendLine("  items:")
        for (special in SpecialItems.SPECIALS) {
            sb.appendLine("    ${special.id}:")
            sb.appendLine("      material: ${special.material.name.lowercase()}   # what item drops")
            sb.appendLine("      gradient: \"${special.gradient}\"   # name color gradient")
            sb.appendLine("      weight: 1   # relative roll weight (0 = disabled)")
        }
        sb.appendLine("")

        // ── Potions / stew ──
        sb.appendLine("# ── POTION / STEW EFFECT POOLS ──────────────────────────────────────────────")
        sb.appendLine("# Which effects random potions and suspicious stews can roll. List effect names")
        sb.appendLine("# (names auto-resolve across Minecraft versions). Invisibility is deliberately")
        sb.appendLine("# absent: an invisible player cannot be fought in a PvP minigame.")
        sb.appendLine("# Quirks:")
        sb.appendLine("#  - A potion's effect is rolled at drop time, not when configured, so editing")
        sb.appendLine("#    this list changes future drops only.")
        sb.appendLine("#  - Suspicious stew effects last 12 seconds (fixed in code).")
        sb.appendLine("potions:")
        sb.appendLine("  effects:")
        POTION_EFFECTS.forEach { sb.appendLine("    - $it") }
        sb.appendLine("  stew-effects:")
        STEW_EFFECTS.forEach { sb.appendLine("    - $it") }

        Files.writeString(path, sb.toString())
    }

    // Builds the full banned-material set for a mode with a human-readable reason for each.
    private fun collectBanned(mode: String, globalBlacklist: Set<Material>, modeBlacklistCfg: Set<Material>): Set<Pair<Material, String>> {
        val profile = LootWeights.PROFILES[mode] ?: LootWeights.PROFILES["normal"]!!
        val result = linkedMapOf<Material, String>()

        fun reasonFor(m: Material): String = when {
            m in globalBlacklist -> "global items.blacklist"
            m in modeBlacklistCfg -> "per-mode blacklist (config.yml)"
            m in Game.UNOBTAINABLE_ITEMS -> "unobtainable / technical block"
            Game.isLootJunk(m) -> "decorative junk (template / horse armor / sherd)"
            else -> "banned by mode loot profile (balance)"
        }

        // Everything the profile weights to 0 among legal items.
        Material.values().forEach { m ->
            if (m.isItem && m !in Game.UNOBTAINABLE_ITEMS && !Game.isLootJunk(m)
                && m !in globalBlacklist && m !in modeBlacklistCfg && profile.weight(m) <= 0) {
                result[m] = reasonFor(m)
            }
        }
        // Plus the explicit banned/technical/junk sets (these may not be "legal" but are still banned).
        (globalBlacklist + modeBlacklistCfg + Game.UNOBTAINABLE_ITEMS).forEach { m ->
            if (m.isItem) result.putIfAbsent(m, reasonFor(m))
        }
        Material.values().forEach { m ->
            if (m.isItem && Game.isLootJunk(m)) result.putIfAbsent(m, reasonFor(m))
        }
        return result.entries.map { it.key to it.value }.toSet()
    }
}
