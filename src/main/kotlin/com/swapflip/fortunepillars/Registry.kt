package com.swapflip.fortunepillars

import com.swapflip.fortunepillars.game.GameModifierCompanion
import com.swapflip.fortunepillars.game.modifier.*
import com.swapflip.fortunepillars.game.mode.*
import com.swapflip.fortunepillars.generation.horizontal.CircularHorGen
import com.swapflip.fortunepillars.generation.horizontal.RandomHorGen
import com.swapflip.fortunepillars.generation.vertical.BlockVertGen
import com.swapflip.fortunepillars.generation.vertical.PillarVertGen

object Registry {
    // associateBy silently drops duplicate keys: log any that slip in so a renamed namespace in
    // one entry can never shadow another one without anyone noticing.
    private fun <T> named(entries: List<T>, namespace: (T) -> String): Map<String, T> =
        entries.groupBy(namespace).also { grouped ->
            grouped.filterValues { it.size > 1 }.keys.forEach {
                FortunePillars.LOG.warn("[Registry] Duplicate namespace \"$it\": only the first entry is used.")
            }
        }.mapValues { (_, v) -> v.first() }

    val horizontalGenerators = named(
        listOf(CircularHorGen, RandomHorGen),
    ) { it.namespace }

    val verticalGenerators = named(
        listOf(BlockVertGen, PillarVertGen),
    ) { it.namespace }

    val modes = named(
        listOf(
            BlockyGame, ActionGame, NormalGame, OpGame,
        ),
    ) { it.gameInfo.namespace }

    val modifiers = named(
        listOf(
            NormalModifier, RisingLavaModifier, TntFallsModifier, SpeedrunnerModifier,
            ArrowRainModifier, LightningModifier, MoonwalkModifier, ChainSwapModifier,
            AblockalypseModifier, LavaFloorModifier, UhcModifier,
            MobWaveModifier, ShrinkingWorldModifier,
        ),
    ) { it.modifierInfo.namespace }

    fun load() {
        FortunePillars.LOG.info("[Registry] Loaded ${horizontalGenerators.size} horizontal generators as Map<Name, HorGen>.")
        FortunePillars.LOG.info("[Registry] Loaded ${verticalGenerators.size} vertical generators as Map<Name, VertGen>.")

        FortunePillars.LOG.info("[Registry] Loaded ${modes.size} modes as Map<Name, Mode>.")
        FortunePillars.LOG.info("[Registry] Loaded ${modifiers.size} game modifiers as Map<Name, Modifier>.")
    }
}
