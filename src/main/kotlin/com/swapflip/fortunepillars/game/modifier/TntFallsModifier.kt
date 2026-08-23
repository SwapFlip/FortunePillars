package com.swapflip.fortunepillars.game.modifier

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.ModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.map.MapBounds
import com.swapflip.fortunepillars.util.ModifierConfigs
import com.swapflip.fortunepillars.util.Ticking
import com.swapflip.fortunepillars.util.playSoundSafe
import com.swapflip.fortunepillars.util.primedTntEntityType
import com.swapflip.fortunepillars.util.setFuseTicks
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Entity

class TntFallsModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<TntFallsModifier>("tnt-falls", ::TntFallsModifier)

    override val info: GameModifierInfo = modifierInfo

    private val intervalSecs = ModifierConfigs.int("tnt-falls", "interval", 10)
    private val startDelaySecs = ModifierConfigs.int("tnt-falls", "start-delay", 10)
    private val fuse = ModifierConfigs.int("tnt-falls", "fuse-ticks", 200)
    private val perDrop = ModifierConfigs.int("tnt-falls", "per-drop", 4)
    private val size = ModifierConfigs.int("tnt-falls", "size", 75)

    private val spawned = mutableListOf<Entity>()

    override fun tick(tick: Ticking.Tick) {
        val playArea = game.playArea(size)
        // TNT falls on its own schedule (every 10 seconds by default), independent of the item cycle.
        if (!tick.isInInterval(game.anchorTick() + startDelaySecs * 20, intervalSecs * 20)) return

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
                val entity = game.world.spawnEntity(location, primedTntEntityType)
                entity.setFuseTicks(fuse)
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
