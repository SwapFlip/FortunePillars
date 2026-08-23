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
import com.swapflip.fortunepillars.util.potionEffectType
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Sound
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

// Moonwalk: once the start delay is over, everyone is on moon physics for the rest of the match -
// continuous Jump Boost III and Slow Falling, so every step is a big floaty leap. The effects are
// reapplied every second with a rolling duration, so they survive effect clears without gaps.
class MoonwalkModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<MoonwalkModifier>("moonwalk", ::MoonwalkModifier)

    override val info: GameModifierInfo = modifierInfo

    private val startDelaySecs = ModifierConfigs.int("moonwalk", "start-delay", 10)
    private val jumpBoost: PotionEffectType? = potionEffectType("JUMP", "JUMP_BOOST")
    private val slowFalling: PotionEffectType? = potionEffectType("SLOW_FALLING")

    private var active = false
    private var warned = false

    override fun tick(tick: Ticking.Tick) {
        // Low gravity kicks in after the start delay and then never turns off.
        if (!active && !tick.isInInterval(game.anchorTick() + startDelaySecs * 20, 20)) return
        active = true

        if (!warned) {
            warned = true
            game.players.forEach { p ->
                p.sendActionBar(p.locale().component("modifier.moonwalk.warning", color = NamedTextColor.GOLD))
            }
            game.players.playSoundSafe(Sound.BLOCK_SLIME_BLOCK_FALL, 1.0f, 1.5f)
        }

        // Reapplied once per second with a 3-second rolling window: cheap (no per-tick packets),
        // and any externally cleared effect is back within a second.
        if (tick.number % 20 != 0) return
        game.players.forEach { p ->
            if (!p.player.isOnline) return@forEach
            jumpBoost?.let { p.player.addPotionEffect(PotionEffect(it, 60, 2, false, false, false)) }
            slowFalling?.let { p.player.addPotionEffect(PotionEffect(it, 60, 0, false, false, false)) }
        }
    }
}
