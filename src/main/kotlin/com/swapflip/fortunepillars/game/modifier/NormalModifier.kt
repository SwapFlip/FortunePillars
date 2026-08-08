package com.swapflip.fortunepillars.game.modifier

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.GameModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo

class NormalModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<NormalModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "normal") }

        override fun constructModifier(game: Game): NormalModifier = NormalModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo
}
