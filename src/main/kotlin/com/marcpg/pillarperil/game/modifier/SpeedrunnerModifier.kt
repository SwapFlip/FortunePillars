package com.marcpg.pillarperil.game.modifier

import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.GameModifierCompanion
import com.marcpg.pillarperil.game.util.GameModifierInfo

class SpeedrunnerModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<SpeedrunnerModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "speedrunner") }

        override fun constructModifier(game: Game): SpeedrunnerModifier = SpeedrunnerModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo
}
