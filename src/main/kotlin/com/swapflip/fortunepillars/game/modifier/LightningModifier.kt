package com.swapflip.fortunepillars.game.modifier

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.ModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.util.ModifierConfigs
import com.swapflip.fortunepillars.util.Ticking
import com.swapflip.fortunepillars.util.enchantment
import com.swapflip.fortunepillars.util.escapeTags
import com.swapflip.fortunepillars.util.playSoundSafe
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.enchantments.Enchantment
import org.bukkit.Sound

// Lightning: a random player gets struck by lightning (visual + damage) and one random piece of
// their gear gets enchanted, so the strike is a curse with a silver lining.
class LightningModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<LightningModifier>("lightning", ::LightningModifier)

    override val info: GameModifierInfo = modifierInfo

    private val intervalSecs = ModifierConfigs.int("lightning", "interval", 30)
    private val startDelaySecs = ModifierConfigs.int("lightning", "start-delay", 10)
    // Half a heart by default: the strike is a cosmetic scare plus its enchant "curse with a
    // silver lining", not a real damage threat.
    private val damage = ModifierConfigs.int("lightning", "damage", 1)

    // The tick of the next strike. Jittered: the first strike lands anywhere between the interval
    // boundaries, and every strike afterwards re-rolls 0-10 extra seconds, so the timing can never
    // be predicted from the interval.
    private var nextStrike = 0

    private val enchantPool = listOfNotNull(
        enchantment("DAMAGE_ALL", "SHARPNESS"), enchantment("ARROW_DAMAGE", "POWER"),
        enchantment("PROTECTION_ENVIRONMENTAL", "PROTECTION"), enchantment("FIRE_ASPECT"),
        enchantment("DIG_SPEED", "EFFICIENCY"), enchantment("PROTECTION_FALL", "FEATHER_FALLING"),
        enchantment("DURABILITY", "UNBREAKING"), enchantment("LOOTING"),
    )

    override fun tick(tick: Ticking.Tick) {
        val now = Bukkit.getCurrentTick()

        // Arm the first strike once the modifier enters its interval window.
        if (nextStrike == 0 && tick.isInInterval(game.anchorTick() + startDelaySecs * 20, intervalSecs * 20))
            nextStrike = now + (0..10).random() * 20
        if (nextStrike == 0 || now < nextStrike) return
        // Re-arm the next strike after the configured interval plus a random 0-10s jitter.
        nextStrike = now + intervalSecs * 20 + (0..10).random() * 20

        // Only strike players who would survive the hit: someone whose health is too low to take
        // the damage is skipped, so the lightning can never be the killing blow. If nobody is
        // healthy enough right now, the strike is skipped entirely.
        val target = game.players.filter { it.player.isOnline && it.player.health > damage }.randomOrNull() ?: return
        game.world.strikeLightningEffect(target.player.location)
        // LIGHTNING kills must never credit an unrelated last-damager: clear the kill-credit
        // window first, so if the strike finishes the player nobody gets the kill for it.
        target.lastDamageTick = Int.MIN_VALUE
        target.lastDamagedBy = null
        target.player.damage(damage.toDouble())

        // Enchant one random piece of gear that can take the chosen enchant. Armor and offhand
        // count too: a strike must be able to enchant anything the player is holding or wearing,
        // not only the hotbar. Each candidate re-reads its own slot so the mutated item is written
        // back into the inventory (inventory getters may return copies).
        val inventory = target.player.inventory
        val chosen = enchantPool.random() ?: return
        val candidates = mutableListOf<(Enchantment) -> Unit>()
        inventory.storageContents.forEachIndexed { i, it ->
            if (it?.type?.isItem == true) candidates += cand@ { ench ->
                val cur = inventory.getItem(i) ?: return@cand
                if (ench.canEnchantItem(cur)) runCatching {
                    cur.addUnsafeEnchantment(ench, (1..2).random())
                    inventory.setItem(i, cur)
                }
            }
        }
        inventory.armorContents?.forEachIndexed { i, it ->
            if (it?.type?.isItem == true) candidates += cand@ { ench ->
                val arr = inventory.armorContents ?: return@cand
                val cur = arr.getOrNull(i) ?: return@cand
                if (ench.canEnchantItem(cur)) runCatching {
                    cur.addUnsafeEnchantment(ench, (1..2).random())
                    arr[i] = cur
                    inventory.armorContents = arr
                }
            }
        }
        val off = inventory.itemInOffHand
        if (off.type.isItem) candidates += { ench ->
            if (ench.canEnchantItem(off)) runCatching {
                off.addUnsafeEnchantment(ench, (1..2).random())
                inventory.setItemInOffHand(off)
            }
        }
        // Only run if at least one slot was a valid candidate, so empty inventories don't waste a spin.
        if (candidates.isNotEmpty()) candidates.random().invoke(chosen)

        target.player.sendActionBar(target.player.locale().component("modifier.lightning.you", color = NamedTextColor.GOLD))
        // The announcement lives on the actionbar instead of chat: strike notifications are
        // momentary info, not something that should scroll the chat.
        game.players.filter { it !== target }.forEach { p ->
            p.sendActionBar(p.locale().component("modifier.lightning.other", target.name().escapeTags(), color = NamedTextColor.GOLD))
        }
        game.players.playSoundSafe(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.8f)
    }
}