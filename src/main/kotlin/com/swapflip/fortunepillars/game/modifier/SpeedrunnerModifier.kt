package com.swapflip.fortunepillars.game.modifier

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.GameModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo

class SpeedrunnerModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<SpeedrunnerModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "speedrunner") }

        override fun constructModifier(game: Game): SpeedrunnerModifier = SpeedrunnerModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo
}
