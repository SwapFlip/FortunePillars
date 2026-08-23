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
        // Blocks make up ~95% of every drop (LootWeights.blocky): bridge, tower and wall off while
        // the rare weapon, tool, food or utility keeps the occasional fight honest.
        override val gameInfo: GameInfo by lazy { GameInfo(this, "blocky", lootWeights = { LootWeights.blocky }) }

        // Starts with a low-damage melee item - the trident is in the blocky mode's banned set, so it
        // can never be a starting weapon.
        val attackItems = listOf(
            Material.STICK,
            Material.STONE_SWORD,   Material.IRON_SWORD,    Material.GOLDEN_SWORD,      Material.DIAMOND_SWORD,
            Material.STONE_AXE,     Material.IRON_AXE,      Material.GOLDEN_AXE,        Material.DIAMOND_AXE,
            Material.STONE_PICKAXE, Material.IRON_PICKAXE,  Material.GOLDEN_PICKAXE,    Material.DIAMOND_PICKAXE,
            Material.STONE_SHOVEL,  Material.IRON_SHOVEL,   Material.GOLDEN_SHOVEL,     Material.DIAMOND_SHOVEL,
        )

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): BlockyGame {
            return BlockyGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo

    override fun init() {
        super.init()

        // The attack weapon goes straight into the player's hand instead of wherever addItem
        // happens to find room - a blocky match is about swinging, not inventory Tetris.
        bukkitPlayers.forEach { pl ->
            pl.inventory.setItem(pl.inventory.heldItemSlot, attackItems.random().toItemStackSafe())
        }
    }
}
