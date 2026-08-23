package com.swapflip.fortunepillars.game.modifier

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.ModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.util.ModifierConfigs
import com.swapflip.fortunepillars.util.Ticking
import com.swapflip.fortunepillars.util.playSoundSafe
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent

// Mob Wave - hostile mobs drop from the sky across the play area on a timer. They can't deal fall
// damage on their first landing (so they slam down intact and start fighting), then behave like any
// other mob. A live cap keeps the wave from overwhelming the server, and every mob is cleaned up
// when the game ends.
class MobWaveModifier(game: Game) : GameModifier(game), Listener {
    companion object : ModifierCompanion<MobWaveModifier>("mob-wave", ::MobWaveModifier)

    override val info: GameModifierInfo = modifierInfo

    private val startDelaySecs = ModifierConfigs.int("mob-wave", "start-delay", 10)
    private val intervalSecs = ModifierConfigs.int("mob-wave", "interval", 15)
    private val perWave = ModifierConfigs.int("mob-wave", "per-wave", 3)
    private val cap = ModifierConfigs.int("mob-wave", "cap", 24)
    private val spawnHeight = ModifierConfigs.int("mob-wave", "spawn-height", 18)
    private val size = ModifierConfigs.int("mob-wave", "size", 75)
    private val mobTypes: List<EntityType> = ModifierConfigs.string(
        "mob-wave", "mob-types", "zombie,skeleton,spider,creeper",
    ).split(",").mapNotNull { runCatching { EntityType.valueOf(it.trim().uppercase()) }.getOrNull() }
        .ifEmpty { listOf(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER) }

    // Mobs currently alive from this modifier, plus those still owed their first-landing fall immunity.
    private val alive = LinkedHashSet<Entity>()
    private val fallImmune = LinkedHashSet<Entity>()

    override fun init() {
        alive.clear()
        fallImmune.clear()
        Bukkit.getPluginManager().registerEvents(this, FortunePillars.PLUGIN)
    }

    override fun tick(tick: Ticking.Tick) {
        if (!tick.isInInterval(game.anchorTick() + startDelaySecs * 20, intervalSecs * 20)) return

        // Drop whatever died on its own so the cap reflects reality.
        alive.removeIf { !it.isValid || it.isDead }

        val area = game.playArea(size)
        val topY = (game.arenaBounds?.maxY ?: area.maxY).coerceAtMost(game.world.maxHeight - 2)
        val spawnY = (topY + spawnHeight).coerceAtMost(game.world.maxHeight - 2)

        repeat(perWave) {
            if (alive.size >= cap) return@repeat
            val type = mobTypes.random()
            val x = (area.minX..area.maxX).random()
            val z = (area.minZ..area.maxZ).random()
            val loc = Location(game.world, x + 0.5, spawnY.toDouble(), z + 0.5)
            val spawned = runCatching { game.world.spawnEntity(loc, type) }.getOrNull() as? LivingEntity ?: return@repeat
            spawned.setRemoveWhenFarAway(false)
            alive += spawned
            fallImmune += spawned
        }

        if (alive.isNotEmpty()) {
            game.players.forEach { p ->
                p.sendActionBar(p.locale().component("modifier.mob-wave.warning", color = NamedTextColor.RED))
            }
            game.players.playSoundSafe(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 0.6f)
        }
    }

    // First-landing fall damage is swallowed so the mob arrives intact; any later fall is normal.
    @EventHandler
    fun onFallDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return
        if (event.entity !in fallImmune) return
        event.isCancelled = true
        fallImmune -= event.entity
    }

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        alive -= event.entity
        fallImmune -= event.entity
    }

    override fun onEnd() {
        HandlerList.unregisterAll(this)
        alive.filter { it.isValid }.forEach { it.remove() }
        alive.clear()
        fallImmune.clear()
    }
}
