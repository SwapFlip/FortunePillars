package com.swapflip.fortunepillars.game

import com.swapflip.fortunepillars.game.util.GameModifierInfo

interface GameModifierCompanion<T : GameModifier> {
    val modifierInfo: GameModifierInfo

    fun constructModifier(game: Game): T
}
