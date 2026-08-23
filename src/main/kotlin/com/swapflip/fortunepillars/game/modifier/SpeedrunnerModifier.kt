package com.swapflip.fortunepillars.game.modifier

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.ModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.util.potionEffectType
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect

// Speedrunner: items drop every 2 seconds and everyone sprints for the whole match. Each player
// starts with a light kit - 32 oak planks to bridge, plus a mixed armor set (gold helmet, leather
// chestplate, iron boots) so early fights are not completely naked.
class SpeedrunnerModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<SpeedrunnerModifier>("speedrun", ::SpeedrunnerModifier)

    override val info: GameModifierInfo = modifierInfo

    override fun init() {
        // Items drop every 2 seconds instead of the configured item time. Set through the modifier
        // (not the queue code) so it also applies when speedrun is picked in Multi Mode.
        game.customItemCountdown = { 2L }

        // Permanent Speed I: infinite duration (no particles, no icon clutter), applied once at
        // init - nothing during the match clears potion effects afterwards.
        val speed = potionEffectType("SPEED", "SWIFTNESS")
        game.players.forEach { p ->
            if (speed != null)
                p.player.addPotionEffect(PotionEffect(speed, PotionEffect.INFINITE_DURATION, 0, false, false, false))

            p.player.inventory.addItem(ItemStack(Material.OAK_PLANKS, 32))
            p.player.inventory.helmet = ItemStack(Material.GOLDEN_HELMET)
            p.player.inventory.chestplate = ItemStack(Material.LEATHER_CHESTPLATE)
            p.player.inventory.boots = ItemStack(Material.IRON_BOOTS)
        }
    }
}
