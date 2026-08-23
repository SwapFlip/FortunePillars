package com.swapflip.fortunepillars.game.modifier

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
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
import org.bukkit.entity.Arrow
import org.bukkit.entity.EntityType
import org.bukkit.util.Vector

// Arrow Rain: once the start delay is over, arrows fall like a steady, endless rain across the
// whole play area - never aimed at anyone, so it is a background hazard instead of a targeted
// shredder. The downpour is capped at `max-arrows` concurrent arrows, so it stays a drizzle no
// matter how long the match runs.
class ArrowRainModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<ArrowRainModifier>("arrow-rain", ::ArrowRainModifier)

    override val info: GameModifierInfo = modifierInfo

    private val startDelaySecs = ModifierConfigs.int("arrow-rain", "start-delay", 10)
    private val perSecond = ModifierConfigs.int("arrow-rain", "per-second", 4)
    private val maxArrows = ModifierConfigs.int("arrow-rain", "max-arrows", 120)
    private val size = ModifierConfigs.int("arrow-rain", "size", 75)

    private var raining = false
    private var warned = false
    // Accumulator keeps the per-second rate smooth when it does not divide evenly into ticks.
    private var spawnAccumulator = 0.0
    private val arrows = mutableSetOf<Arrow>()

    override fun tick(tick: Ticking.Tick) {
        // The rain starts once the start delay is over and then never stops for the rest of the match.
        if (!raining && !tick.isInInterval(game.anchorTick() + startDelaySecs * 20, 20)) return
        raining = true

        if (!warned) {
            warned = true
            game.players.forEach { p ->
                p.sendActionBar(p.locale().component("modifier.arrow-rain.warning", color = NamedTextColor.RED))
            }
            game.players.playSoundSafe(Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.5f)
        }

        arrows.removeIf { !it.isValid }
        // Hard cap on concurrent arrows: when the sky is already full, no new ones spawn until
        // some land or despawn - the rain stays limitable instead of stacking up insanely.
        if (arrows.size >= maxArrows) return

        // Real-rain density: a few arrows per second at random spots across the arena. Each arrow
        // spawns high above the play area and falls straight down, so it is visible before it lands.
        spawnAccumulator += perSecond / 20.0
        while (spawnAccumulator >= 1 && arrows.size < maxArrows) {
            spawnAccumulator--
            val area = game.playArea(size)
            val y = (area.maxY + 30).coerceAtMost(game.world.maxHeight - 1)
            val loc = Location(game.world, (area.minX..area.maxX).random() + 0.5, y.toDouble(), (area.minZ..area.maxZ).random() + 0.5)
            val arrow = game.world.spawnEntity(loc, EntityType.ARROW) as? Arrow ?: return
            arrow.velocity = Vector(0.0, -0.6, 0.0)
            arrow.shooter = null
            // The rain must never arm the players: rain arrows can't be picked up, so they can't
            // be farmed into an infinite bow supply.
            arrow.pickupStatus = org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED
            arrows += arrow
        }
    }

    // Take every remaining arrow out of the air when the game ends, so none of them rain down on
    // the winner during the celebration.
    override fun onEnd() {
        arrows.removeIf { !it.isValid }
        arrows.forEach { it.remove() }
        arrows.clear()
    }
}
