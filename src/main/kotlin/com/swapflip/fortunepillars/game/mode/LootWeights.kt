package com.swapflip.fortunepillars.game.mode

import org.bukkit.Material

// Weight profiles for the mode loot pools. Every mode draws from ALL legal items (anything that
// is an item, enabled by the server's feature flags and not in the global or per-mode blacklist);
// the mode's identity comes from these weights, not from a curated material list. A weight of 0
// bans the material for that mode, so the profile doubles as the built-in per-mode blacklist.
//
// The profiles are exposed publicly (PROFILES / categoryWeights / banned / overrides) so the
// per-mode config files (modes/<mode>.yml) can both document the defaults and fall back to them
// when a section is omitted. See ModeConfigGenerator (file writer) and ModeConfigs (reader).
object LootWeights {
    enum class Kind { BLOCK, FOOD, TOOL, WEAPON, ARMOR, EGG, POTION, AMMO, UTILITY }

    // Tiers above iron are "high damage" in a pillar fight: diamond and netherite gear, tridents,
    // elytra, totems and enchanted apples would instantly decide a match between naked players.
    // The normal and blocky modes never hand them out (op and action un-ban them on purpose).
    private val DIAMOND_GEAR = setOf(
        Material.DIAMOND_SWORD, Material.DIAMOND_AXE, Material.DIAMOND_PICKAXE, Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE,
        Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
    )
    private val NETHERITE_GEAR = setOf(
        Material.NETHERITE_SWORD, Material.NETHERITE_AXE, Material.NETHERITE_PICKAXE, Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE,
        Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
    )
    private val ENDGAME = setOf(
        Material.TRIDENT, Material.ELYTRA, Material.TOTEM_OF_UNDYING, Material.ENCHANTED_GOLDEN_APPLE, Material.NETHER_STAR,
    )

    // TNT no longer drops from the regular loot pool: it is a custom item that only appears
    // through the rare special-item roll (SpecialItems.TNT), so it stays obtainable without
    // flooding every match as a common block drop.
    private val CUSTOM_ONLY = setOf(Material.TNT)

    // Classifies a material into one of the loot categories. The order of the checks matters:
    // specific families are tested before the generic "is a block" fallback, so e.g. TNT (a block)
    // and ender pearls (non-blocks) land in the right bucket.
    fun kindOf(material: Material): Kind = when {
        material.name.endsWith("_SPAWN_EGG") -> Kind.EGG
        material == Material.POTION || material == Material.SPLASH_POTION || material == Material.LINGERING_POTION || material == Material.SUSPICIOUS_STEW -> Kind.POTION
        material.name.endsWith("_ARROW") || material == Material.ARROW -> Kind.AMMO
        material.isEdible -> Kind.FOOD
        material.name.endsWith("_PICKAXE") || material.name.endsWith("_SHOVEL") || material.name.endsWith("_HOE")
            || material == Material.FLINT_AND_STEEL || material == Material.SHEARS || material == Material.BRUSH -> Kind.TOOL
        material.name.endsWith("_SWORD") || material.name.endsWith("_AXE")
            || material == Material.BOW || material == Material.CROSSBOW || material == Material.TRIDENT
            || material == Material.SHIELD || material == Material.FISHING_ROD -> Kind.WEAPON
        material.name.endsWith("_HELMET") || material.name.endsWith("_CHESTPLATE") || material.name.endsWith("_LEGGINGS") || material.name.endsWith("_BOOTS") -> Kind.ARMOR
        material.isBlock -> Kind.BLOCK
        else -> Kind.UTILITY
    }

    // Flowers are plentiful (many distinct types all share the block weight, so combined they drop
    // constantly): every flower drops at weight 1 instead of the category weight, so a bloom only
    // shows up now and then. Glowstone dust is equally useless in a fight - same treatment.
    private val FLOWERS = setOf(
        Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET,
        Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP,
        Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.WITHER_ROSE,
        Material.TORCHFLOWER, Material.PINK_PETALS, Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY,
    )
    private val COMMON_JUNK = (FLOWERS.associateWith { 1 }) + (Material.GLOWSTONE_DUST to 1)

    // A single mode's weighting profile. `categoryWeights` is the fallback for every material of a
    // kind; the `*Weights` maps override specific materials (e.g. iron swords drop rarer than the
    // generic weapon weight). `banned` is the built-in per-mode veto list (weight 0 for all of it).
    data class Profile(
        val banned: Set<Material>,
        val categoryWeights: Map<Kind, Int>,
        val weaponWeights: Map<Material, Int> = emptyMap(),
        val armorWeights: Map<Material, Int> = emptyMap(),
        val foodWeights: Map<Material, Int> = emptyMap(),
        val blockWeights: Map<Material, Int> = emptyMap(),
        val utilityWeights: Map<Material, Int> = emptyMap(),
    ) {
        // The weight of a material for this profile: 0 means banned, otherwise the material's
        // override or its category's default weight.
        fun weight(material: Material): Int {
            if (material in banned) return 0
            return when (kindOf(material)) {
                Kind.FOOD -> foodWeights[material] ?: categoryWeights.getValue(Kind.FOOD)
                Kind.WEAPON -> weaponWeights[material] ?: categoryWeights.getValue(Kind.WEAPON)
                Kind.ARMOR -> armorWeights[material] ?: categoryWeights.getValue(Kind.ARMOR)
                Kind.BLOCK -> blockWeights[material] ?: categoryWeights.getValue(Kind.BLOCK)
                Kind.TOOL -> categoryWeights.getValue(Kind.TOOL)
                Kind.EGG -> categoryWeights.getValue(Kind.EGG)
                Kind.POTION -> categoryWeights.getValue(Kind.POTION)
                Kind.AMMO -> categoryWeights.getValue(Kind.AMMO)
                Kind.UTILITY -> utilityWeights[material] ?: categoryWeights.getValue(Kind.UTILITY)
            }
        }
    }

    // Normal - the balanced mode. No high-damage items (no diamond/netherite/trident/totem/elytra),
    // but everything else: blocks, food, low-tier gear, tools, combat eggs, potions and utility.
    private val normalProfile = Profile(
        banned = DIAMOND_GEAR + NETHERITE_GEAR + ENDGAME + CUSTOM_ONLY,
        categoryWeights = mapOf(
            Kind.BLOCK to 3, Kind.FOOD to 4, Kind.TOOL to 3, Kind.WEAPON to 4, Kind.ARMOR to 4,
            Kind.EGG to 2, Kind.POTION to 3, Kind.AMMO to 3, Kind.UTILITY to 3,
        ),
        blockWeights = COMMON_JUNK, utilityWeights = COMMON_JUNK,
        weaponWeights = mapOf(
            Material.IRON_SWORD to 1, Material.IRON_AXE to 1,
            Material.GOLDEN_SWORD to 2, Material.GOLDEN_AXE to 2,
            Material.BOW to 2, Material.CROSSBOW to 1, Material.SHIELD to 1, Material.FISHING_ROD to 2,
        ),
        armorWeights = mapOf(
            Material.IRON_HELMET to 3, Material.IRON_CHESTPLATE to 3, Material.IRON_LEGGINGS to 3, Material.IRON_BOOTS to 3,
            Material.GOLDEN_HELMET to 2, Material.GOLDEN_CHESTPLATE to 2, Material.GOLDEN_LEGGINGS to 2, Material.GOLDEN_BOOTS to 2,
        ),
        foodWeights = mapOf(Material.GOLDEN_APPLE to 2),
    )

    // Blocky - blocks mainly. Everything else (low-tier gear, food, tools, utility, potions) drops
    // occasionally, hostile spawn eggs never do.
    private val blockyProfile = Profile(
        banned = DIAMOND_GEAR + NETHERITE_GEAR + ENDGAME + CUSTOM_ONLY,
        categoryWeights = mapOf(
            Kind.BLOCK to 8, Kind.FOOD to 1, Kind.TOOL to 1, Kind.WEAPON to 1, Kind.ARMOR to 1,
            Kind.EGG to 0, Kind.POTION to 1, Kind.AMMO to 1, Kind.UTILITY to 1,
        ),
        blockWeights = COMMON_JUNK, utilityWeights = COMMON_JUNK,
    )

    // Action - fast paced and flashy: NOTHING is off the table. Netherite, elytra, totems,
    // enchanted golden apples and nether stars all drop, so a single cycle can flip the whole
    // fight. Blocks still lead every drop; TNT stays custom-only (it is its own special item).
    private val actionProfile = Profile(
        banned = CUSTOM_ONLY,
        categoryWeights = mapOf(
            Kind.BLOCK to 6, Kind.FOOD to 3, Kind.TOOL to 2, Kind.WEAPON to 2, Kind.ARMOR to 2,
            Kind.EGG to 2, Kind.POTION to 3, Kind.AMMO to 3, Kind.UTILITY to 3,
        ),
        blockWeights = COMMON_JUNK, utilityWeights = COMMON_JUNK,
        weaponWeights = mapOf(
            Material.WOODEN_SWORD to 2, Material.WOODEN_AXE to 2,
            Material.STONE_SWORD to 3, Material.STONE_AXE to 3,
            Material.GOLDEN_SWORD to 3, Material.GOLDEN_AXE to 3,
            Material.IRON_SWORD to 4, Material.IRON_AXE to 4,
            Material.DIAMOND_SWORD to 3, Material.DIAMOND_AXE to 3,
            Material.TRIDENT to 2, Material.BOW to 5, Material.CROSSBOW to 3, Material.SHIELD to 3, Material.FISHING_ROD to 3,
        ),
        armorWeights = mapOf(
            Material.LEATHER_HELMET to 2, Material.LEATHER_CHESTPLATE to 2, Material.LEATHER_LEGGINGS to 2, Material.LEATHER_BOOTS to 2,
            Material.GOLDEN_HELMET to 3, Material.GOLDEN_CHESTPLATE to 3, Material.GOLDEN_LEGGINGS to 3, Material.GOLDEN_BOOTS to 3,
            Material.CHAINMAIL_HELMET to 4, Material.CHAINMAIL_CHESTPLATE to 4, Material.CHAINMAIL_LEGGINGS to 4, Material.CHAINMAIL_BOOTS to 4,
            Material.IRON_HELMET to 4, Material.IRON_CHESTPLATE to 4, Material.IRON_LEGGINGS to 4, Material.IRON_BOOTS to 4,
            Material.DIAMOND_HELMET to 3, Material.DIAMOND_CHESTPLATE to 3, Material.DIAMOND_LEGGINGS to 3, Material.DIAMOND_BOOTS to 3,
        ),
        foodWeights = mapOf(Material.GOLDEN_APPLE to 2),
    )

    // OP - the highest gear chance of all modes (diamond is the most common gear drop, netherite
    // stays rare), while blocks remain a large share of every drop. Nothing is banned: nether
    // stars and raw TNT drop too (placed TNT auto-primes with a 3s fuse). Boss eggs (wither,
    // elder guardian, warden) are globally blacklisted, so even OP never drops them.
    private val opProfile = Profile(
        banned = emptySet(),
        categoryWeights = mapOf(
            Kind.BLOCK to 5, Kind.FOOD to 3, Kind.TOOL to 3, Kind.WEAPON to 2, Kind.ARMOR to 2,
            Kind.EGG to 3, Kind.POTION to 3, Kind.AMMO to 3, Kind.UTILITY to 3,
        ),
        blockWeights = COMMON_JUNK, utilityWeights = COMMON_JUNK,
        weaponWeights = mapOf(
            Material.STONE_SWORD to 2, Material.STONE_AXE to 2,
            Material.IRON_SWORD to 3, Material.IRON_AXE to 3, Material.IRON_PICKAXE to 2, Material.IRON_SHOVEL to 2, Material.IRON_HOE to 2,
            Material.DIAMOND_SWORD to 5, Material.DIAMOND_AXE to 5, Material.DIAMOND_PICKAXE to 4, Material.DIAMOND_SHOVEL to 4, Material.DIAMOND_HOE to 4,
            Material.NETHERITE_SWORD to 3, Material.NETHERITE_AXE to 3, Material.NETHERITE_PICKAXE to 2, Material.NETHERITE_SHOVEL to 2, Material.NETHERITE_HOE to 2,
            Material.TRIDENT to 3, Material.BOW to 6, Material.CROSSBOW to 5, Material.SHIELD to 4,
        ),
        armorWeights = mapOf(
            Material.IRON_HELMET to 3, Material.IRON_CHESTPLATE to 3, Material.IRON_LEGGINGS to 3, Material.IRON_BOOTS to 3,
            Material.DIAMOND_HELMET to 5, Material.DIAMOND_CHESTPLATE to 5, Material.DIAMOND_LEGGINGS to 5, Material.DIAMOND_BOOTS to 5,
            Material.NETHERITE_HELMET to 3, Material.NETHERITE_CHESTPLATE to 3, Material.NETHERITE_LEGGINGS to 3, Material.NETHERITE_BOOTS to 3,
        ),
        foodWeights = mapOf(Material.ENCHANTED_GOLDEN_APPLE to 3, Material.GOLDEN_APPLE to 4),
    )

    // All four profiles keyed by their game-mode namespace. Used by the config generator to write
    // the documented defaults and by ModeConfigs as the fallback when a mode file omits a section.
    val PROFILES: Map<String, Profile> = mapOf(
        "normal" to normalProfile,
        "blocky" to blockyProfile,
        "action" to actionProfile,
        "op" to opProfile,
    )

    val normal: (Material) -> Int = normalProfile::weight
    val blocky: (Material) -> Int = blockyProfile::weight
    val action: (Material) -> Int = actionProfile::weight
    val op: (Material) -> Int = opProfile::weight

    // Resolves a mode namespace to its weighting function, falling back to the balanced normal
    // profile for unknown modes (so a typo'd namespace still yields a sane pool).
    fun of(mode: String): (Material) -> Int = PROFILES[mode]?.let { it::weight } ?: normal
}
