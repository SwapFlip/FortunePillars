package com.swapflip.fortunepillars.player

import com.marcpg.libpg.lang.string
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.util.MINI_MESSAGE
import com.swapflip.fortunepillars.util.ModeConfigs
import com.swapflip.fortunepillars.util.enchantment
import com.swapflip.fortunepillars.util.potionEffectType
import com.swapflip.fortunepillars.util.potionType
import com.swapflip.fortunepillars.util.WeightedBag
import com.swapflip.fortunepillars.util.setPotionTypeSafe
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.CrossbowMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.inventory.meta.SuspiciousStewMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionType
import java.util.Locale

// Special items drop from the power-up pool. They get colored, gradient names plus a lore line
// explaining exactly how to use them, behave differently from their vanilla counterparts when used
// (see the interaction handlers in PlayerEvents), and glow so they stand out among regular loot.
object SpecialItems {
    val KEY = NamespacedKey(FortunePillars.PLUGIN, "special")

    // Marks items that came from the power-up pool so modifiers (e.g. UHC) can tell them apart
    // from regular loot and leave them untouched.
    private val POWER_UP_KEY = NamespacedKey(FortunePillars.PLUGIN, "power_up")

    fun isPowerUp(item: ItemStack): Boolean =
        item.itemMeta?.persistentDataContainer?.has(POWER_UP_KEY, PersistentDataType.STRING) == true

    private fun tagPowerUp(item: ItemStack): ItemStack {
        item.itemMeta = item.itemMeta?.apply { persistentDataContainer.set(POWER_UP_KEY, PersistentDataType.STRING, "1") }
        return item
    }

    data class Special(
        val id: String,
        val material: Material,
        val gradient: String,
        val nameKey: String,
        val usageKey: String,
    )

    // A special item as configured per-mode (modes/<mode>.yml). Wraps a built-in special's
    // identity/name but lets the config override the material, gradient and roll weight.
    data class ConfigSpecial(
        val id: String,
        val material: Material,
        val gradient: String,
        val nameKey: String,
        val usageKey: String,
        val weight: Int,
    ) {
        // Rebuilds the canonical Special with the config's material/gradient applied.
        fun toSpecial(): Special = Special(id, material, gradient, nameKey, usageKey)
    }

    val SUPER_STAR = Special("super-star", Material.NETHER_STAR, "#FFD700:#FF8C00", "special.super-star.name", "special.super-star.usage")
    val FIREBALL = Special("fireball", Material.FIRE_CHARGE, "#FF4500:#FF8C00", "special.fireball.name", "special.fireball.usage")
    val AID_PLATFORM = Special("aid-platform", Material.SLIME_BLOCK, "#32CD32:#00FF7F", "special.aid-platform.name", "special.aid-platform.usage")
    val TNT = Special("tnt", Material.TNT, "#FF0000:#FFA500", "special.tnt.name", "special.tnt.usage")
    val LEVITATION_FEATHER = Special("levitation-feather", Material.FEATHER, "#FFFFFF:#ADD8E6", "special.levitation-feather.name", "special.levitation-feather.usage")

    // The built-in specials. The per-mode config can enable/disable each (by weight) and
    // override its material/gradient; their display name and usage lore stay tied to the locale.
    val SPECIALS = listOf(SUPER_STAR, FIREBALL, AID_PLATFORM, TNT, LEVITATION_FEATHER)

    // Returns the special definition of an item, or null when it's not a special item.
    fun of(item: ItemStack): Special? {
        val id = item.itemMeta?.persistentDataContainer?.get(KEY, PersistentDataType.STRING) ?: return null
        return SPECIALS.firstOrNull { it.id == id }
    }

    // Gives the item its colored, gradient name, usage lore, the special tag and an enchantment
    // glint so it never blends in with the regular loot.
    fun apply(item: ItemStack, special: Special, locale: Locale): ItemStack {
        val meta = item.itemMeta ?: return item
        meta.displayName(MINI_MESSAGE.deserialize("<gradient:${special.gradient}>${locale.string(special.nameKey)}</gradient>"))
        meta.lore(listOf(
            MINI_MESSAGE.deserialize(locale.string(special.usageKey)),
            MINI_MESSAGE.deserialize(locale.string("special.tag")),
        ))
        meta.persistentDataContainer.set(KEY, PersistentDataType.STRING, special.id)
        // Glint without an actual enchant: the enchant is hidden, only the glow shows.
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS)
        val glint = enchantment("DURABILITY", "UNBREAKING") ?: enchantment("MENDING")
        if (glint != null) runCatching { meta.addEnchant(glint, 1, true) }
            .onFailure { FortunePillars.LOG.warn("Could not add a hidden glint to a special item.", it) }
        item.itemMeta = meta
        return item
    }

    // A random drop from the power-up pool for a mode: useful survival items and the special items.
    // The pool (items + potions + weights) is read from the mode's config; when the config leaves
    // it empty, the plugin's built-in default pool is used instead. `excludeTypes` re-rolls outcomes
    // whose material is unwanted (e.g. no snowballs while the lava rises); after a few failed rolls
    // a guaranteed-safe golden apple is returned.
    fun randomPowerUp(mode: String, excludeTypes: Set<Material> = emptySet()): ItemStack {
        val items = ModeConfigs.powerUpItems(mode)
        val potions = ModeConfigs.powerUpPotions(mode)

        // Built-in fallback when the mode file defines no power-up pool at all.
        if (items.isEmpty() && potions.isEmpty()) return tagPowerUp(legacyPowerUp(excludeTypes))

        repeat(4) {
            val entries = items.entries.map { (material, w) -> MaterialEntry(material) to w } +
                potions.entries.map { (effect, w) -> PotionEntry(effect) to w }
            val roll = weightedPick(entries)
            val item = when (roll) {
                is MaterialEntry -> ItemStack(roll.material).also { if (roll.material == Material.SNOWBALL) it.amount = 4 }
                is PotionEntry -> potion(roll.type)
                null -> return tagPowerUp(ItemStack(Material.GOLDEN_APPLE))
            }
            if (item.type !in excludeTypes) return tagPowerUp(item)
        }
        return tagPowerUp(ItemStack(Material.GOLDEN_APPLE))
    }

    // Hard-coded default power-up pool, used only when a mode file omits `power-ups.items`/`potions`.
    private fun legacyPowerUp(excludeTypes: Set<Material>): ItemStack {
        repeat(4) {
            val item = when ((0..9).random()) {
                0 -> ItemStack(Material.GOLDEN_APPLE)
                1 -> ItemStack(Material.ENDER_PEARL)
                2 -> ItemStack(Material.FLINT_AND_STEEL)
                3 -> ItemStack(Material.SNOWBALL).apply { amount = 4 }
                4 -> potion(PotionType.SPEED)
                5 -> potion(potionType("LEAPING", "JUMP_BOOST") ?: PotionType.SPEED)
                else -> potion(PotionType.STRENGTH)
            }
            if (item.type !in excludeTypes) return item
        }
        return ItemStack(Material.GOLDEN_APPLE)
    }

    // A random special item for a mode: reads the mode's `specials.items` (weighted by `weight`),
    // falling back to the four built-in specials when the config defines none. `excludeIds` filters
    // out unwanted specials (e.g. no fireballs while the lava rises).
    fun randomSpecial(locale: Locale, mode: String, excludeIds: Set<String> = emptySet()): ItemStack {
        val config = ModeConfigs.specialItems(mode)
        val basePool = if (config.isEmpty()) SPECIALS.map { ConfigSpecial(it.id, it.material, it.gradient, it.nameKey, it.usageKey, 1) }
        else config.values
        val pool = basePool.filter { it.id !in excludeIds }.ifEmpty { basePool }

        val pick = weightedSpecialPick(pool) ?: return apply(ItemStack(SUPER_STAR.material), SUPER_STAR, locale)
        return apply(ItemStack(pick.material), pick.toSpecial(), locale)
    }

    private fun specialItem(special: Special, locale: Locale): ItemStack = apply(ItemStack(special.material), special, locale)

    private fun potion(type: PotionType): ItemStack {
        val item = ItemStack(Material.POTION)
        val meta = item.itemMeta as? PotionMeta ?: return item
        meta.setPotionTypeSafe(type)
        item.itemMeta = meta
        return item
    }

    // The effect types random drinkable/splash/lingering potions can roll, from the loot pool.
    // Invisibility is deliberately absent: an invisible player cannot be fought in a PvP
    // minigame, so it must never drop. Read from the mode config; falls back to this built-in list.
    private val randomPotions = listOfNotNull(
        potionType("SPEED", "SWIFTNESS"), potionType("STRENGTH"), potionType("JUMP_BOOST", "LEAPING"),
        potionType("HEALING", "INSTANT_HEAL"), potionType("HARMING", "INSTANT_DAMAGE"), potionType("POISON"),
        potionType("SLOWNESS"), potionType("WEAKNESS"), potionType("REGENERATION"), potionType("NIGHT_VISION"),
        potionType("FIRE_RESISTANCE"), potionType("WATER_BREATHING"),
    )

    // The effect types random suspicious stews can roll: a mix of helpful and harmful effects.
    private val randomStewEffects = listOfNotNull(
        potionEffectType("SPEED"), potionEffectType("JUMP", "JUMP_BOOST"),
        potionEffectType("REGENERATION"), potionEffectType("NIGHT_VISION"), potionEffectType("FIRE_RESISTANCE"),
        potionEffectType("BLINDNESS"), potionEffectType("WEAKNESS"), potionEffectType("SLOWNESS"),
        potionEffectType("POISON"), potionEffectType("HUNGER"), potionEffectType("NAUSEA"),
        potionEffectType("LEVITATION"), potionEffectType("LUCK"), potionEffectType("SATURATION"),
    )

    // Turns a raw loot-pool material into a usable drop: crossbows arrive loaded, potions roll a
    // random effect, suspicious stew gets a random effect, and snowballs stay in small bundles.
    // The effect pools are read from the mode config and fall back to the built-in lists when empty.
    fun refine(stack: ItemStack, mode: String): ItemStack {
        when (stack.type) {
            Material.CROSSBOW -> {
                val meta = stack.itemMeta as? CrossbowMeta
                if (meta != null) {
                    meta.setChargedProjectiles(listOf(ItemStack(Material.ARROW)))
                    stack.itemMeta = meta
                }
            }
            Material.SUSPICIOUS_STEW -> {
                val meta = stack.itemMeta as? SuspiciousStewMeta
                val effects = ModeConfigs.stewEffects(mode).ifEmpty { randomStewEffects }
                val effect = effects.randomOrNull()
                if (meta != null && effect != null) {
                    runCatching { meta.addCustomEffect(PotionEffect(effect, 12 * 20, 0), true) }
                        .onFailure { FortunePillars.LOG.warn("Could not add a random effect to a suspicious stew.", it) }
                    stack.itemMeta = meta
                }
            }
            Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION -> {
                val meta = stack.itemMeta as? PotionMeta
                val effects = ModeConfigs.potionEffects(mode).ifEmpty { randomPotions }
                val type = effects.randomOrNull()
                if (meta != null && type != null) {
                    meta.setPotionTypeSafe(type)
                    stack.itemMeta = meta
                }
            }
            Material.SNOWBALL -> if (stack.amount > 4) stack.amount = 4
            else -> Unit
        }
        return stack
    }

    // Randomly enchants a dropped weapon, tool or piece of armor. The per-mode config
    // (`enchant.families`) decides the chance per item family and which enchants (with max level)
    // can roll; when a family is not configured, the built-in pool below is used as a fallback.
    // Enchanted drops are rare and modest: the level is capped at the family's max (default 2), so a
    // dropped sword can never be stronger than a moderately-enchanted one from the start.
    fun maybeEnchant(stack: ItemStack, mode: String): ItemStack {
        val type = stack.type
        val family = familyOf(type) ?: return stack
        val config = ModeConfigs.enchantFamilies(mode)[family]

        val pool: List<Enchantment> = if (config != null && config.enchants.isNotEmpty()) {
            if ((0..99).random() >= config.chance) return stack
            config.enchants.keys.toList()
        } else {
            val fallback = legacyEnchantPool(type) ?: return stack
            if ((0..99).random() >= ModeConfigs.enchantChance(mode).coerceAtMost(10)) return stack
            fallback
        }
        if (pool.isEmpty()) return stack

        val enchant = pool.random()
        val maxLevel = (config?.enchants?.get(enchant) ?: 2).coerceAtLeast(1)
        val level = (1..maxLevel).random()
        if (enchant.canEnchantItem(stack))
            runCatching { stack.addUnsafeEnchantment(enchant, level) }
        return stack
    }

    // Which config "family" a material belongs to. Mirrors the family split used in the config so
    // `enchant.families.<family>` lines up with the items that drop.
    private fun familyOf(type: Material): String? = when {
        type.name.endsWith("_SWORD") -> "sword"
        type.name.endsWith("_AXE") -> "axe"
        type.name.endsWith("_PICKAXE") || type.name.endsWith("_SHOVEL") || type.name.endsWith("_HOE") -> "pickaxe"
        type.name.endsWith("_HELMET") -> "helmet"
        type.name.endsWith("_CHESTPLATE") -> "chestplate"
        type.name.endsWith("_LEGGINGS") -> "leggings"
        type.name.endsWith("_BOOTS") -> "boots"
        type == Material.BOW -> "bow"
        type == Material.CROSSBOW -> "crossbow"
        type == Material.TRIDENT -> "trident"
        else -> null
    }

    // Built-in enchant pool per material, used as a fallback when a family is not configured.
    private fun legacyEnchantPool(type: Material): List<Enchantment>? = when {
        type.name.endsWith("_SWORD") -> listOfNotNull(enchantment("DAMAGE_ALL", "SHARPNESS"), enchantment("FIRE_ASPECT"), enchantment("KNOCKBACK"), enchantment("LOOTING"))
        type.name.endsWith("_AXE") -> listOfNotNull(enchantment("DAMAGE_ALL", "SHARPNESS"), enchantment("DIG_SPEED", "EFFICIENCY"))
        type.name.endsWith("_PICKAXE") || type.name.endsWith("_SHOVEL") || type.name.endsWith("_HOE") -> listOfNotNull(enchantment("DIG_SPEED", "EFFICIENCY"), enchantment("LOOT_BONUS_BLOCKS", "FORTUNE"), enchantment("SILK_TOUCH"))
        type.name.endsWith("_HELMET") -> listOfNotNull(enchantment("PROTECTION_ENVIRONMENTAL", "PROTECTION"), enchantment("OXYGEN", "RESPIRATION"), enchantment("WATER_WORKER", "AQUA_AFFINITY"))
        type.name.endsWith("_CHESTPLATE") || type.name.endsWith("_LEGGINGS") -> listOfNotNull(enchantment("PROTECTION_ENVIRONMENTAL", "PROTECTION"), enchantment("PROTECTION_FIRE", "FIRE_PROTECTION"), enchantment("PROTECTION_PROJECTILE", "PROJECTILE_PROTECTION"))
        type.name.endsWith("_BOOTS") -> listOfNotNull(enchantment("PROTECTION_ENVIRONMENTAL", "PROTECTION"), enchantment("PROTECTION_FALL", "FEATHER_FALLING"), enchantment("DEPTH_STRIDER"))
        type == Material.BOW -> listOfNotNull(enchantment("ARROW_DAMAGE", "POWER"), enchantment("ARROW_KNOCKBACK", "PUNCH"), enchantment("ARROW_FIRE", "FLAME"), enchantment("ARROW_INFINITE", "INFINITY"))
        type == Material.CROSSBOW -> listOfNotNull(enchantment("MULTISHOT"), enchantment("PIERCING"), enchantment("QUICK_CHARGE"))
        type == Material.TRIDENT -> listOfNotNull(enchantment("IMPALING"), enchantment("LOYALTY"), enchantment("RIPTIDE"))
        else -> null
    }

    // Tiny weighted-pick helper for the power-up pool (materials and potion effects share a weight).
    private sealed interface PoolEntry
    private data class MaterialEntry(val material: Material) : PoolEntry
    private data class PotionEntry(val type: PotionType) : PoolEntry

    private fun weightedPick(entries: List<Pair<PoolEntry, Int>>): PoolEntry? {
        if (entries.isEmpty()) return null
        return WeightedBag(entries.toMap()).random()
    }

    private fun weightedSpecialPick(entries: Collection<ConfigSpecial>): ConfigSpecial? {
        if (entries.isEmpty()) return null
        return WeightedBag(entries.associateWith { it.weight }).random()
    }
}
