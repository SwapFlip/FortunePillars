package com.swapflip.fortunepillars.game

import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.player.PillarPlayer
import com.swapflip.fortunepillars.util.Ticking
import org.bukkit.inventory.ItemStack

abstract class GameModifier(
    val game: Game,
) : Ticking {
    abstract val info: GameModifierInfo

    open fun init() {}
    open fun customBuild() {}
    override fun tick(tick: Ticking.Tick) {}

    open fun onItemCycle() {}
    open fun onItemReceive(item: ItemStack): ItemStack = item
    open fun onPlayerDeath(player: PillarPlayer) {}
    open fun onPostPlayerDeath(player: PillarPlayer) {}
    open fun onEnd() {}
}
