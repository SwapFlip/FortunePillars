package com.swapflip.fortunepillars.util

import java.nio.file.Files
import java.nio.file.Path

// Writes the documented default `modifiers/<modifier>.yml` file on first run. Each file mirrors the
// plugin's built-in defaults (the same keys that lived under `modifiers.<modifier>` in config.yml)
// but surfaces them as editable, documented keys. The config.yml section is kept as a fallback.
object ModifierConfigGenerator {
    private data class Key(val name: String, val default: Int, val docs: List<String>)
    private data class ModifierInfo(val description: List<String>, val keys: List<Key>)

    private val MODIFIER_DATA = mapOf(
        "lava-rises" to ModifierInfo(
            description = listOf(
                "Lava Rises - the lava level climbs over time, forcing players upward.",
                "The lava starts just below the map's spectator spawn (or `start-y` when the",
                "map has none) and floods the whole pit, then rises one block every item",
                "cycle plus `interval-extra` seconds. Layers place in batches to avoid lag.",
            ),
            keys = listOf(
                Key("start-y", 30, listOf(
                    "The y-coordinate the lava starts at and rises from when the map has no",
                    "spectator spawn. Ignored when the map provides one.",
                )),
                Key("spectator-offset", 70, listOf(
                    "Blocks below the spectator spawn the lava starts at, when the map has a",
                    "spectator spawn. The lava never starts above the players' feet.",
                )),
                Key("start-delay", 10, listOf("Seconds after the game starts before the lava begins to rise.")),
                Key("size", 75, listOf(
                    "Side length of the square play area the lava covers, centered on the",
                    "map's spectator spawn.",
                )),
                Key("interval-extra", 2, listOf(
                    "Extra seconds added to the mode's item cycle to get the time between two",
                    "lava rises (e.g. a 5s cycle -> a rise every 7s).",
                )),
            ),
        ),
        "tnt-falls" to ModifierInfo(
            description = listOf(
                "TNT Falls - TNT drops from the sky on its own timer with a configurable fuse.",
                "TNT spawns from the world's height limit and falls into the play area.",
            ),
            keys = listOf(
                Key("interval", 10, listOf("Seconds between each round of falling TNT.")),
                Key("start-delay", 10, listOf("Seconds after the game starts before TNT begins to fall.")),
                Key("fuse-ticks", 200, listOf(
                    "The fuse duration of the falling TNT in ticks (20 ticks = 1 second).",
                    "Shorter fuses give less time to flee.",
                )),
                Key("per-drop", 4, listOf("TNT dropped per round, at separate random positions in the play area.")),
                Key("size", 75, listOf("Side length of the square play area the TNT falls into.")),
            ),
        ),
        "arrow-rain" to ModifierInfo(
            description = listOf(
                "Arrow Rain - a steady drizzle of arrows over the whole play area once the",
                "start delay is over. Capped at `max-arrows` concurrent arrows so it never",
                "gets overwhelming.",
            ),
            keys = listOf(
                Key("start-delay", 10, listOf("Seconds after the game starts before the rain begins.")),
                Key("per-second", 4, listOf("Arrows spawned per second, spread randomly over the play area.")),
                Key("max-arrows", 120, listOf(
                    "Maximum arrows in the air at once; no new ones spawn until some land.",
                    "Raise for a denser storm, lower to keep it mild.",
                )),
                Key("size", 75, listOf("Side length of the square play area the arrows fall into.")),
            ),
        ),
        "lightning" to ModifierInfo(
            description = listOf(
                "Lightning - a random player is struck (visual + a tiny damage tick) and one",
                "random item (hotbar, armor or offhand) gets enchanted. Players who would not",
                "survive the hit are skipped.",
            ),
            keys = listOf(
                Key("interval", 30, listOf("Seconds between lightning strikes.")),
                Key("start-delay", 10, listOf("Seconds after the game starts before the first strike.")),
                Key("damage", 1, listOf(
                    "Damage in half-hearts (1 = half a heart). Kept tiny on purpose so the",
                    "strike is a fright, not a kill.",
                )),
            ),
        ),
        "moonwalk" to ModifierInfo(
            description = listOf(
                "Moonwalk - continuous moon gravity: everyone gets Jump Boost III and Slow",
                "Falling for the rest of the match once the start delay is over.",
            ),
            keys = listOf(
                Key("start-delay", 10, listOf("Seconds after the game starts before the effect is applied.")),
            ),
        ),
        "chain-swap" to ModifierInfo(
            description = listOf(
                "Chain Swap - every player's position shifts to the next player's spot, in a",
                "ring. Nobody is swapped into a spot below `min-y`.",
            ),
            keys = listOf(
                Key("interval", 30, listOf("Seconds between swaps.")),
                Key("start-delay", 10, listOf("Seconds after the game starts before the first swap.")),
                Key("min-y", 32, listOf("Nobody is ever swapped into a spot below this y-coordinate.")),
            ),
        ),
        "ablockalypse" to ModifierInfo(
            description = listOf(
                "Block Rain (Ablockalypse) - random blocks rain from the sky in small batches.",
                "Literally every placeable block can fall; fluids, portals, fire and illegal/",
                "technical blocks are excluded automatically.",
            ),
            keys = listOf(
                Key("interval", 15, listOf("Seconds between bursts.")),
                Key("start-delay", 10, listOf("Seconds after the game starts before the first burst.")),
                Key("per-drop", 30, listOf("Blocks dropped per burst.")),
                Key("size", 75, listOf("Side length of the square play area the blocks fall into.")),
            ),
        ),
        "lava-floor" to ModifierInfo(
            description = listOf(
                "Lava Floor - standing on a block heats it up: after `stand-time` seconds it",
                "ignites into yellow wool, then cooks one stage further every `stage-time`",
                "seconds until it becomes lava.",
            ),
            keys = listOf(
                Key("start-delay", 10, listOf("Seconds after the game starts before heating begins.")),
                Key("stand-time", 5, listOf("Seconds of standing before a block turns into yellow wool.")),
                Key("stage-time", 3, listOf("Seconds between each further stage (yellow -> orange -> red -> lava).")),
            ),
        ),
        "uhc" to ModifierInfo(
            description = listOf(
                "UHC - natural regeneration is disabled and everyone starts with 5 golden",
                "apples. A slice of the regular drops becomes dedicated healing instead.",
            ),
            keys = listOf(
                Key("heal-chance", 20, listOf(
                    "Percent chance a regular drop is converted into a Regeneration or Instant",
                    "Health potion instead of a normal item.",
                )),
            ),
        ),
        "mob-wave" to ModifierInfo(
            description = listOf(
                "Mob Wave - hostile mobs drop from the sky across the play area on a timer.",
                "Their first landing deals no fall damage, so they slam down intact and start",
                "fighting. A live cap keeps the swarm from overwhelming the server.",
            ),
            keys = listOf(
                Key("start-delay", 10, listOf("Seconds after the game starts before the first wave.")),
                Key("interval", 15, listOf("Seconds between waves.")),
                Key("per-wave", 3, listOf("Mobs dropped per wave, at separate random positions.")),
                Key("cap", 24, listOf("Maximum mobs alive at once; no new ones spawn until some die.")),
                Key("spawn-height", 18, listOf("Blocks above the play-area top the mobs spawn at, then fall from.")),
                Key("size", 75, listOf("Side length of the square play area the mobs fall into.")),
            ),
        ),
        "shrinking-world" to ModifierInfo(
            description = listOf(
                "Shrinking World - the arena's boundary creeps inward every second, drawn as a",
                "red-particle edge. Players caught outside the shrinking ring are eliminated, so",
                "the play space keeps tightening until only the smallest core remains.",
            ),
            keys = listOf(
                Key("start-delay", 10, listOf("Seconds after the game starts before the world begins to shrink.")),
                Key("shrink-rate", 1, listOf("Blocks the boundary moves inward each second.")),
                Key("min-radius", 20, listOf(
                    "The world stops shrinking once its radius (or half-width) reaches this,",
                    "leaving a final core to fight over.",
                )),
            ),
        ),
    )

    fun generate(modifier: String, path: Path) {
        val info = MODIFIER_DATA[modifier] ?: return
        val sb = StringBuilder()

        sb.appendLine("# ############################################################################### #")
        sb.appendLine("# #                  Fortune Pillars - $modifier modifier config                # #")
        sb.appendLine("# ############################################################################### #")
        sb.appendLine("#")
        info.description.forEach { sb.appendLine("# $it") }
        sb.appendLine("#")
        sb.appendLine("# Every value is editable and picked up live (no restart needed) when")
        sb.appendLine("# `config.auto-reload` is enabled, or by /pp-config reload. Anything you delete")
        sb.appendLine("# falls back to the matching key under `modifiers.$modifier` in config.yml, and")
        sb.appendLine("# then to the plugin's built-in default - so you only document what you change.")
        sb.appendLine("#")

        for (key in info.keys) {
            key.docs.forEach { sb.appendLine("# $it") }
            sb.appendLine("${key.name}: ${key.default}")
            sb.appendLine("")
        }

        Files.writeString(path, sb.toString())
    }
}
