package com.swapflip.fortunepillars.game.modifier

import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameBorder
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.ModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.util.ModifierConfigs
import com.swapflip.fortunepillars.util.Ticking
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import kotlin.math.cos
import kotlin.math.sin

// Shrinking World - the arena's boundary creeps inward every second, drawn as a red-particle edge.
// Players caught outside the shrinking ring are eliminated by the normal out-of-bounds check, so the
// play space keeps tightening until only the smallest core remains.
class ShrinkingWorldModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<ShrinkingWorldModifier>("shrinking-world", ::ShrinkingWorldModifier)

    override val info: GameModifierInfo = modifierInfo

    private val startDelaySecs = ModifierConfigs.int("shrinking-world", "start-delay", 10)
    private val shrinkRate = ModifierConfigs.int("shrinking-world", "shrink-rate", 1)
    private val minRadius = ModifierConfigs.int("shrinking-world", "min-radius", 20)

    override fun tick(tick: Ticking.Tick) {
        if (!tick.isSecond(game.anchorTick() + startDelaySecs * 20)) return
        val border = game.border ?: return
        // Stop once the world has shrunk down to the configured minimum radius.
        if (currentRadius(border) <= minRadius + shrinkRate) return
        border.shrinkBy(shrinkRate)
        drawBoundary(border)
    }

    private fun currentRadius(border: GameBorder): Int = when (val b = border.currentBoundary()) {
        is GameBorder.CircleBoundary -> b.radius
        is GameBorder.RectBoundary -> minOf(b.maxX - b.minX, b.maxZ - b.minZ) / 2
    }

    // Red-particle ring along the current (shrunk) deadly boundary, at a few heights so the edge
    // reads as a wall rather than a single line.
    private fun drawBoundary(border: GameBorder) {
        val layers = pickLayers(border)
        when (val b = border.currentBoundary()) {
            is GameBorder.CircleBoundary -> {
                val steps = 96
                for (i in 0 until steps) {
                    val a = 2 * Math.PI * i / steps
                    val x = (b.cx + b.radius * cos(a)).toInt()
                    val z = (b.cz + b.radius * sin(a)).toInt()
                    for (y in layers) spawnRed(x, y, z)
                }
            }
            is GameBorder.RectBoundary -> {
                val steps = maxOf(8, (b.maxX - b.minX) / 4)
                for (y in layers) {
                    for (i in 0..steps) {
                        val t = i.toDouble() / steps
                        spawnRed((b.minX + (b.maxX - b.minX) * t).toInt(), y, b.minZ)
                        spawnRed((b.minX + (b.maxX - b.minX) * t).toInt(), y, b.maxZ)
                        spawnRed(b.minX, y, (b.minZ + (b.maxZ - b.minZ) * t).toInt())
                        spawnRed(b.maxX, y, (b.minZ + (b.maxZ - b.minZ) * t).toInt())
                    }
                }
            }
        }
    }

    private fun pickLayers(border: GameBorder): List<Int> {
        val (lo, hi) = when (val b = border.currentBoundary()) {
            is GameBorder.CircleBoundary -> b.minY to b.maxY
            is GameBorder.RectBoundary -> b.minY to b.maxY
        }
        if (hi <= lo) return listOf(lo)
        val n = 4
        return (0 until n).map { lo + (hi - lo) * it / (n - 1) }
    }

    private fun spawnRed(x: Int, y: Int, z: Int) {
        game.world.spawnParticle(
            Particle.REDSTONE, x + 0.5, y + 0.5, z + 0.5, 0, 0.0, 0.0, 0.0, 0.0,
            Particle.DustOptions(Color.fromRGB(255, 40, 40), 1.5f),
        )
    }
}
