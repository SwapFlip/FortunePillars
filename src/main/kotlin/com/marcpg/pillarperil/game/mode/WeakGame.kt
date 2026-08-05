package com.marcpg.pillarperil.game.mode

import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameCompanion
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.util.GameInfo
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

class WeakGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<WeakGame> {
        val bannedItems = setOf(
            Material.NETHERITE_SWORD, Material.NETHERITE_AXE, Material.NETHERITE_PICKAXE, Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE,
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, Material.NETHERITE_INGOT,
            Material.DIAMOND_SWORD, Material.DIAMOND_AXE, Material.DIAMOND_PICKAXE, Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.DIAMOND, Material.EMERALD, Material.TRIDENT, Material.BOW, Material.CROSSBOW,
            Material.TNT, Material.TNT_MINECART, Material.END_CRYSTAL, Material.LAVA_BUCKET,
            Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE, Material.TOTEM_OF_UNDYING,
            Material.WITHER_SKELETON_SKULL, Material.DRAGON_EGG, Material.ELYTRA, Material.BEACON,
        )

        override val gameInfo: GameInfo by lazy { GameInfo(this, "weak") { it !in bannedItems } }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): WeakGame {
            return WeakGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo
}
