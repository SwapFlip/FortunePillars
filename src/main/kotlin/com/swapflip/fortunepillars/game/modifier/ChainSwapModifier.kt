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
import org.bukkit.Sound

// Chain Swap: every player's position shifts to the next player's spot (a chain), so the whole
// arena rearranges at once instead of only two players swapping.
class ChainSwapModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<ChainSwapModifier>("chain-swap", ::ChainSwapModifier)

    override val info: GameModifierInfo = modifierInfo

    private val intervalSecs = ModifierConfigs.int("chain-swap", "interval", 30)
    private val startDelaySecs = ModifierConfigs.int("chain-swap", "start-delay", 10)
    // Nobody is ever swapped into a spot below this height, even if it is above the death height:
    // low ground (pits, void-adjacent ledges) is a death sentence mid-fight.
    private val minY = ModifierConfigs.int("chain-swap", "min-y", 32)

    override fun tick(tick: Ticking.Tick) {
        if (!tick.isInInterval(game.anchorTick() + startDelaySecs * 20, intervalSecs * 20)) return

        val alive = game.players.filter { it.player.isOnline }
        if (alive.size < 2) return

        val locations = alive.map { it.player.location.clone() }
        alive.forEachIndexed { i, p ->
            val dest = locations[(i + 1) % locations.size]
            // Swapping into the void would kill the player - leave them in place instead.
            if (dest.y < game.deathHeight || dest.y < minY) return@forEachIndexed
            // Swapping into a solid block (or into the side of a pillar the target player is
            // standing inside) would suffocate or launch the player - skip the swap instead of
            // teleporting them into a wall.
            val feet = dest.block
            if (feet.type.isAir && feet.getRelative(org.bukkit.block.BlockFace.UP).type.isAir) {
                p.player.teleport(dest)
                // Teleporting preserves the falling speed; reset it so the swap can never translate
                // into fall damage on arrival.
                p.player.fallDistance = 0f
                p.sendActionBar(p.locale().component("modifier.chain-swap.now", color = NamedTextColor.GREEN))
            }
        }
        // Teleporting grants 3 seconds of invulnerability; clear it so the swap doesn't make
        // players unhittable and swallow hits.
        alive.forEach { it.player.noDamageTicks = 0 }
        alive.playSoundSafe(Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f)
    }
}