package com.swapflip.fortunepillars.util

import com.swapflip.fortunepillars.FortunePillars
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.attribute.Attributable
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionType

fun <T> getIfClassExists(requiredClass: String, hasClass: () -> T, alternative: () -> T): T {
    try {
        Class.forName(requiredClass)
    } catch (_: ClassNotFoundException) {
        return alternative()
    }
    // Return outside the try to not catch exceptions possibly created by hasClass itself.
    return hasClass()
}

// Game-rule resolution, version-independent: prefers GameRule.getByName (still functional while
// deprecated), then the new GameRules class (1.21.11+), then the legacy GameRule field. Results are
// cached per (old, new) key, and failures are logged once and skipped - a missing rule must never
// crash the game start.
private val gameRuleCache = mutableMapOf<Pair<String, String>, GameRule<*>?>()
private var gameRuleErrorLogged = false

fun <T : Any> World.setGameRuleSafe(oldName: String, newName: String, value: T): Boolean {
    val rule = gameRuleCache.getOrPut(oldName to newName) {
        GameRule.getByName(newName)
            ?: GameRule.getByName(oldName)
            ?: runCatching { Class.forName("org.bukkit.GameRules").getField(newName).get(null) as GameRule<*> }.getOrNull()
            ?: runCatching { Class.forName("org.bukkit.GameRule").getField(oldName).get(null) as GameRule<*> }.getOrNull()
    } ?: return false

    // The compiled setGameRule(GameRule, Object) call descriptor becomes NoSuchMethodError once
    // the legacy API is removed for real: catch it and skip instead of aborting game start.
    return runCatching {
        @Suppress("UNCHECKED_CAST")
        setGameRule(rule as GameRule<T>, value)
        true
    }.getOrElse {
        if (!gameRuleErrorLogged) {
            FortunePillars.LOG.warn("Could not set game rule $oldName/$newName: ${it.javaClass.simpleName}: ${it.message}. Game-rule settings are skipped.", it)
            gameRuleErrorLogged = true
        }
        false
    }
}

val cachedAttributes = mutableMapOf<String, Attribute>()
fun Attributable.getAttributeSafe(name: String): AttributeInstance? {
    if (name in cachedAttributes)
        return getAttribute(cachedAttributes[name]!!)

    val attribute = Attribute::class.java.fields.firstOrNull { it.name.endsWith(name) }?.get(null) ?: return null
    cachedAttributes[name] = attribute as Attribute
    return getAttribute(attribute)
}

private var cachedItemStackCreator: ((Material) -> ItemStack)? = null
fun Material.toItemStackSafe(): ItemStack {
    if (cachedItemStackCreator == null) {
        try {
            val of = ItemStack::class.java.getMethod("of", Material::class.java)
            cachedItemStackCreator = { of(null, it) as ItemStack }
        } catch (_: NoSuchMethodException) {
            cachedItemStackCreator = { ItemStack(it) }
        } catch (e: ReflectiveOperationException) {
            throw RuntimeException(e)
        }
    }

    return cachedItemStackCreator!!(this)
}

// The primed-TNT entity type (renamed to PRIMED_TNT in newer versions) and a safe fuse setter.
val primedTntEntityType: EntityType by lazy {
    runCatching { EntityType.valueOf("PRIMED_TNT") }.getOrElse { EntityType.valueOf("TNT") }
}

private val fuseSetter: java.lang.reflect.Method? by lazy {
    val clazz = runCatching { Class.forName("org.bukkit.entity.TNTPrimed") }
        .getOrElse { runCatching { Class.forName("org.bukkit.entity.PrimedTnt") }.getOrNull() } ?: return@lazy null
    runCatching { clazz.getMethod("setFuseTicks", Int::class.javaPrimitiveType) }.getOrNull()
}

fun Entity.setFuseTicks(ticks: Int) {
    runCatching { fuseSetter?.invoke(this, ticks) }
}

// Bukkit renamed a bunch of constants between versions (e.g. DAMAGE_ALL -> SHARPNESS, JUMP ->
// JUMP_BOOST). These resolve the constant by trying every known name against the runtime class.
private val potionTypeFields by lazy { PotionType::class.java.fields.associateBy { it.name } }
fun potionType(name: String, vararg alternates: String): PotionType? =
    (listOf(name) + alternates).firstNotNullOfOrNull { potionTypeFields[it]?.get(null) as? PotionType }

private val effectTypeFields by lazy { PotionEffectType::class.java.fields.associateBy { it.name } }
fun potionEffectType(name: String, vararg alternates: String): PotionEffectType? =
    (listOf(name) + alternates).firstNotNullOfOrNull { effectTypeFields[it]?.get(null) as? PotionEffectType }

private val enchantmentFields by lazy { Enchantment::class.java.fields.associateBy { it.name } }
fun enchantment(name: String, vararg alternates: String): Enchantment? =
    (listOf(name) + alternates).firstNotNullOfOrNull { enchantmentFields[it]?.get(null) as? Enchantment }

// Sets the potion's base type, working on both the legacy (setPotionType) and the modern
// (setBasePotionType) Bukkit APIs.
private val potionTypeSetters by lazy { listOf("setBasePotionType", "setPotionType") }
fun PotionMeta.setPotionTypeSafe(type: PotionType) {
    val setter = potionTypeSetters.firstNotNullOfOrNull { name ->
        runCatching { PotionMeta::class.java.getMethod(name, PotionType::class.java) }.getOrNull()
    } ?: return
    runCatching { setter.invoke(this, type) }
}

// Enchant glint override (added to the API in a later version than we compile against).
private val glintOverrideSetter by lazy {
    runCatching { ItemMeta::class.java.getMethod("setEnchantmentGlintOverride", Boolean::class.javaPrimitiveType) }.getOrNull()
}
fun ItemMeta.setGlintOverride(glow: Boolean) {
    runCatching { glintOverrideSetter?.invoke(this, glow) }
}

// Block-damage overlay for a breaking block (added to the API in a later version than we compile
// against). Cosmetic only - safely does nothing on servers without it.
private val sendBlockDamageMethod by lazy {
    World::class.java.methods.firstOrNull { it.name == "sendBlockDamage" && it.parameterCount == 3 }
}
fun World.sendBlockDamageSafe(location: Location, progress: Float, entityId: Int) {
    runCatching { sendBlockDamageMethod?.invoke(this, location, progress, entityId) }
}

// Firework-spark particle (renamed FIREWORKS_SPARK -> FIREWORK between Minecraft versions):
// resolved once per session, null when neither name exists. Cosmetic only.
private val sparkParticle by lazy {
    runCatching { Particle.valueOf("FIREWORK") }
        .getOrElse { runCatching { Particle.valueOf("FIREWORKS_SPARK") }.getOrNull() }
}

fun World.spawnSparkParticle(location: Location, count: Int, spread: Double, speed: Double) {
    val particle = sparkParticle ?: return
    runCatching { spawnParticle(particle, location, count, spread, spread * 1.2, spread, speed) }
}
