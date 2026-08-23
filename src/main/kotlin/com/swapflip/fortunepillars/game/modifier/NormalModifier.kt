package com.swapflip.fortunepillars.game.modifier

import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.ModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo

class NormalModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<NormalModifier>("normal", ::NormalModifier)

    override val info: GameModifierInfo = modifierInfo
}
