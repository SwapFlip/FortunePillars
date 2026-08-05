package com.marcpg.pillarperil.game.modifier

import com.marcpg.libpg.util.component
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.GameModifierCompanion
import com.marcpg.pillarperil.game.util.GameModifierInfo
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.Ticking
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Location
import kotlin.math.hypot
import kotlin.math.max

class BorderShrinksModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<BorderShrinksModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "border-shrinks") }

        override fun constructModifier(game: Game): BorderShrinksModifier = BorderShrinksModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo

    private val step = Configuration.provider.getInt("modifiers.border-shrinks.step", 4)
    private val minRadius = Configuration.provider.getInt("modifiers.border-shrinks.min-radius", 5)

    private var centerX = 0.0
    private var centerZ = 0.0
    private var radius = 0.0
    private var active = false

    override fun init() {
        val bounds = game.arenaBounds ?: return
        val worldBorder = game.world.worldBorder

        centerX = (bounds.minX + bounds.maxX) / 2.0
        centerZ = (bounds.minZ + bounds.maxZ) / 2.0
        radius = max(hypot(bounds.maxX - centerX, bounds.maxZ - centerZ), hypot(bounds.minX - centerX, bounds.minZ - centerZ)) + 1

        worldBorder.center = Location(game.world, centerX, 0.0, centerZ)
        worldBorder.size = radius * 2
        active = true
    }

    override fun onItemCycle() {
        val worldBorder = game.world.worldBorder

        radius = max(minRadius.toDouble(), radius - step)
        worldBorder.size = radius * 2

        game.players.forEach { p ->
            p.showTitle(Title.title(
                component("Border Shrinks!", NamedTextColor.RED),
                component("Safe area is now ${radius.toInt()} blocks.", NamedTextColor.YELLOW)
            ))
        }
    }

    override fun tick(tick: Ticking.Tick) {
        if (!active || !tick.isSecond(game.startingTick)) return

        game.players.forEach { p ->
            val location = p.player.location
            if (hypot(location.x - centerX, location.z - centerZ) > radius)
                p.player.damage(1.0)
        }
    }

    override fun onEnd() {
        if (active) {
            game.world.worldBorder.reset()
            active = false
        }
    }
}
