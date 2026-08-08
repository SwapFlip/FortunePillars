package com.swapflip.fortunepillars.game.modifier

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.GameModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.map.MapBounds
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.Ticking
import com.swapflip.fortunepillars.util.playSoundSafe
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType

class TntFallsModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<TntFallsModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "tnt-falls") }

        override fun constructModifier(game: Game): TntFallsModifier = TntFallsModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo

    private val intervalSecs = Configuration.provider.getInt("modifiers.tnt-falls.interval", 10)
    private val startDelaySecs = Configuration.provider.getInt("modifiers.tnt-falls.start-delay", 60)
    private val fuse = Configuration.provider.getInt("modifiers.tnt-falls.fuse-ticks", 200)
    private val perDrop = Configuration.provider.getInt("modifiers.tnt-falls.per-drop", 3)
    private val size = Configuration.provider.getInt("modifiers.tnt-falls.size", 100)

    private val spawned = mutableListOf<Entity>()

    private val tntType: EntityType by lazy {
        runCatching { EntityType.valueOf("PRIMED_TNT") }.getOrElse { EntityType.valueOf("TNT") }
    }

    private val fuseSetter: java.lang.reflect.Method? by lazy {
        val clazz = runCatching { Entity::class.java.classLoader.loadClass("org.bukkit.entity.PrimedTnt") }
            .getOrElse { runCatching { Entity::class.java.classLoader.loadClass("org.bukkit.entity.TNTPrimed") }.getOrNull() }
        clazz?.getMethod("setFuseTicks", Int::class.javaPrimitiveType)
    }

    override fun tick(tick: Ticking.Tick) {
        val playArea = game.playArea(size)
        // TNT falls on its own schedule (every 10 seconds by default), independent of the item cycle.
        if (!tick.isInInterval(game.startingTick + startDelaySecs * 20, intervalSecs * 20)) return

        game.players.playSoundSafe(Sound.ENTITY_TNT_PRIMED, 1.0f, 1.2f)
        game.players.forEach { p ->
            if (p.player.isOnline)
                p.player.sendActionBar(p.locale().component("modifier.tnt-falls.warning", color = NamedTextColor.RED))
        }

        // Pick separate, non-adjacent random spots so the drops spread over the arena instead of
        // stacking into one clump (and never the same spot twice).
        runCatching {
            randomSpots(playArea, perDrop).forEach { (x, z) ->
                val location = Location(game.world, x + 0.5, (game.world.maxHeight - 1).toDouble(), z + 0.5)
                val entity = game.world.spawnEntity(location, tntType)
                runCatching { fuseSetter?.invoke(entity, fuse) }
                spawned += entity
            }
        }.onFailure {
            FortunePillars.LOG.error("[TntFalls] Could not spawn raining TNT.", it)
        }
    }

    // Picks up to `count` random (x, z) spots that are at least 4 blocks apart (and thus never
    // resolve to the same 2x2 cell), retrying a few times before giving up on the last spot.
    private fun randomSpots(area: MapBounds, count: Int): List<Pair<Int, Int>> {
        val spots = mutableListOf<Pair<Int, Int>>()
        var attempts = 0
        while (spots.size < count && attempts < count * 8) {
            attempts++
            val x = (area.minX..area.maxX).random()
            val z = (area.minZ..area.maxZ).random()
            if (spots.none { (ox, oz) -> (ox - x) * (ox - x) + (oz - z) * (oz - z) < 16 })
                spots += x to z
        }
        return spots
    }

    override fun onEnd() {
        spawned.forEach { it.remove() }
        spawned.clear()
    }
}
