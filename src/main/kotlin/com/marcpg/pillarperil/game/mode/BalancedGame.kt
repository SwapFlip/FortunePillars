package com.marcpg.pillarperil.game.mode

import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameCompanion
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.util.GameInfo
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

class BalancedGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>) : Game(id, center, bukkitPlayers, modifiers) {
    companion object : GameCompanion<BalancedGame> {
        val bannedItems = listOf(
            Material.TNT, Material.TNT_MINECART, Material.BEDROCK, Material.END_CRYSTAL,
            Material.WITHER_SKELETON_SKULL, Material.NETHER_STAR, Material.LAVA_BUCKET, Material.DRAGON_EGG,
        )

        override val gameInfo: GameInfo by lazy { GameInfo(this, "balanced") { it !in bannedItems } }

        override fun constructGame(id: String, center: Location, bukkitPlayers: List<Player>, modifiers: List<GameModifier>): BalancedGame {
            return BalancedGame(id, center, bukkitPlayers, modifiers)
        }
    }

    override val info: GameInfo = gameInfo
}
