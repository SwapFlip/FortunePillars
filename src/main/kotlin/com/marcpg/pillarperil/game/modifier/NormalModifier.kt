package com.marcpg.pillarperil.game.modifier

import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.GameModifierCompanion
import com.marcpg.pillarperil.game.util.GameModifierInfo

class NormalModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<NormalModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "normal") }

        override fun constructModifier(game: Game): NormalModifier = NormalModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo
}
