package com.swapflip.fortunepillars.player

import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect

data class Kit(
    val slots: Map<Int, ItemStack> = emptyMap(),
    val add: List<ItemStack> = emptyList(),
    val potionEffects: List<PotionEffect> = emptyList(),
)

fun PillarPlayer.applyKit(kit: Kit) {
    kit.slots.forEach { (slot, stack) -> player.inventory.setItem(slot, stack) }
    if (kit.add.isNotEmpty()) player.inventory.addItem(*kit.add.toTypedArray())
    kit.potionEffects.forEach { player.addPotionEffect(it) }
}
