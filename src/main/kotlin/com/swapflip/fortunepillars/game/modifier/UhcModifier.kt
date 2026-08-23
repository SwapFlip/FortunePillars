package com.swapflip.fortunepillars.game.modifier

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.ModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.player.SpecialItems
import com.swapflip.fortunepillars.util.ModifierConfigs
import com.swapflip.fortunepillars.util.potionEffectType
import com.swapflip.fortunepillars.util.potionType
import com.swapflip.fortunepillars.util.setGameRuleSafe
import com.swapflip.fortunepillars.util.setPotionTypeSafe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffect

// UHC: natural regeneration is disabled for the match - every heart has to be earned with golden
// apples, potions and power-ups. Everyone starts with 5 golden apples, and a slice of the regular
// drops is converted into dedicated healing (a 10s Regeneration or an Instant Health potion), so
// healing shows up noticeably more often than the mode's own pool would give. Re-enabled when the
// game ends.
class UhcModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<UhcModifier>("uhc", ::UhcModifier)

    override val info: GameModifierInfo = modifierInfo

    // Percent of regular drops that become a dedicated healing potion instead.
    private val healChance = ModifierConfigs.int("uhc", "heal-chance", 20)

    private val regenEffect = potionEffectType("REGENERATION")
    private val instantHealth = potionEffectType("INSTANT_HEALTH", "HEAL")
    private val regenType = potionType("REGENERATION")
    private val instantHealthType = potionType("INSTANT_HEALTH", "HEAL")

    override fun init() {
        game.world.setGameRuleSafe("NATURAL_REGENERATION", "NATURAL_HEALTH_REGENERATION", false)

        // Starting apples: with regeneration off, everyone gets a small heal reserve to survive
        // the early fight.
        game.players.forEach { p ->
            p.player.inventory.addItem(ItemStack(Material.GOLDEN_APPLE, 5))
        }
    }

    override fun onItemReceive(item: ItemStack): ItemStack {
        // Special items and power-ups are never converted - only regular loot rolls can become
        // a healing potion.
        if (SpecialItems.of(item) != null) return item
        if (SpecialItems.isPowerUp(item)) return item
        if ((0..99).random() >= healChance) return item

        // Alternate between a 10-second Regeneration drink and an Instant Health drink.
        return if ((0..1).random() == 0) {
            val effect = regenEffect ?: return item
            val type = regenType ?: return item
            val potion = ItemStack(Material.POTION)
            val meta = potion.itemMeta as? PotionMeta ?: return item
            meta.setPotionTypeSafe(type)
            runCatching { meta.addCustomEffect(PotionEffect(effect, 10 * 20, 0), true) }
            potion.itemMeta = meta
            potion
        } else {
            val effect = instantHealth ?: return item
            val type = instantHealthType ?: return item
            val potion = ItemStack(Material.POTION)
            val meta = potion.itemMeta as? PotionMeta ?: return item
            meta.setPotionTypeSafe(type)
            runCatching { meta.addCustomEffect(PotionEffect(effect, 1, 0), true) }
            potion.itemMeta = meta
            potion
        }
    }

    override fun onEnd() {
        // The original value is restored centrally by Game.cleanup(); this is only the UHC-off
        // revert for worlds where no other game re-enabled it.
        game.world.setGameRuleSafe("NATURAL_REGENERATION", "NATURAL_HEALTH_REGENERATION", true)
    }
}
