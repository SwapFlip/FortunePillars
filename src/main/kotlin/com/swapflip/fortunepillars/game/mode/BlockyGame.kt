package com.swapflip.fortunepillars.game.mode

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameCompanion
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.util.GameInfo
import com.swapflip.fortunepillars.util.toItemStackSafe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

class BlockyGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<BlockyGame> {
        override val gameInfo: GameInfo by lazy { GameInfo(this, "blocky") { it.isBlock && it.isSolid } }

        val attackItems = listOf(
            Material.STICK,         Material.TRIDENT,
            Material.STONE_SWORD,   Material.IRON_SWORD,    Material.GOLDEN_SWORD,      Material.DIAMOND_SWORD,     Material.NETHERITE_SWORD,
            Material.STONE_AXE,     Material.IRON_AXE,      Material.GOLDEN_AXE,        Material.DIAMOND_AXE,       Material.NETHERITE_AXE,
            Material.STONE_PICKAXE, Material.IRON_PICKAXE,  Material.GOLDEN_PICKAXE,    Material.DIAMOND_PICKAXE,   Material.NETHERITE_PICKAXE,
            Material.STONE_SHOVEL,  Material.IRON_SHOVEL,   Material.GOLDEN_SHOVEL,     Material.DIAMOND_SHOVEL,    Material.NETHERITE_SHOVEL
        )

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): BlockyGame {
            return BlockyGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo

    override fun init() {
        super.init()

        bukkitPlayers.forEach { it.inventory.addItem(attackItems.random().toItemStackSafe()) }
    }
}
