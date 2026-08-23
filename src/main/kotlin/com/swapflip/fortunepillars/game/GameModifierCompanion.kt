package com.swapflip.fortunepillars.game

import com.swapflip.fortunepillars.game.util.GameModifierInfo

interface GameModifierCompanion<T : GameModifier> {
    val modifierInfo: GameModifierInfo

    fun constructModifier(game: Game): T
}

// Shared companion base: removes the per-modifier modifierInfo/constructModifier boilerplate.
// Subclasses only need the namespace and a constructor reference:
//   companion object : ModifierCompanion<XModifier>("x-y", ::XModifier)
abstract class ModifierCompanion<T : GameModifier>(
    namespace: String,
    private val constructor: (Game) -> T,
) : GameModifierCompanion<T> {
    override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, namespace) }

    override fun constructModifier(game: Game): T = constructor(game)
}