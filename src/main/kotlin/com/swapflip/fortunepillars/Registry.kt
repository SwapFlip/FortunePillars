package com.swapflip.fortunepillars

import com.swapflip.fortunepillars.game.GameModifierCompanion
import com.swapflip.fortunepillars.game.modifier.*
import com.swapflip.fortunepillars.game.mode.*
import com.swapflip.fortunepillars.generation.horizontal.CircularHorGen
import com.swapflip.fortunepillars.generation.horizontal.RandomHorGen
import com.swapflip.fortunepillars.generation.vertical.BlockVertGen
import com.swapflip.fortunepillars.generation.vertical.PillarVertGen

object Registry {
    val horizontalGenerators = listOf(
        CircularHorGen,
        RandomHorGen,
    ).associateBy { it.namespace }

    val verticalGenerators = listOf(
        BlockVertGen,
        PillarVertGen,
    ).associateBy { it.namespace }

    val modes = listOf(
        BlockyGame,
        ChaosGame,
        ClassicGame,
        ItemOnlyGame,
        ItemShuffleGame,
        OriginalGame,
        PlayerShuffleGame,
        NormalGame,
        BalancedGame,
        SwapperGame,
        ShuffleGame,
        WeakGame,
        OpGame,
    ).associateBy { it.gameInfo.namespace }

    val modifiers = listOf(
        NormalModifier,
        RisingLavaModifier,
        TntFallsModifier,
        SpeedrunnerModifier,
    ).associateBy { it.modifierInfo.namespace }

    fun load() {
        FortunePillars.LOG.info("[Registry] Loaded ${horizontalGenerators.size} horizontal generators as Map<Name, HorGen>.")
        FortunePillars.LOG.info("[Registry] Loaded ${verticalGenerators.size} vertical generators as Map<Name, VertGen>.")

        FortunePillars.LOG.info("[Registry] Loaded ${modes.size} modes as Map<Name, Mode>.")
        FortunePillars.LOG.info("[Registry] Loaded ${modifiers.size} game modifiers as Map<Name, Modifier>.")
    }
}
