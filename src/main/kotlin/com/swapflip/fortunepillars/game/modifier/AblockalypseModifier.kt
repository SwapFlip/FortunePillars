package com.swapflip.fortunepillars.game.modifier

import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.GameModifier
import com.swapflip.fortunepillars.game.ModifierCompanion
import com.swapflip.fortunepillars.game.util.GameModifierInfo
import com.swapflip.fortunepillars.map.BlockPos
import com.swapflip.fortunepillars.util.ModifierConfigs
import com.swapflip.fortunepillars.util.Ticking
import com.swapflip.fortunepillars.util.playSoundSafe
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.Location

// Block Rain (internally still the "ablockalypse" namespace for config compatibility): random
// blocks rain down from the sky onto the arena in small, batched bursts. Literally every
// placeable block can fall - only fluids, air variants, portals, fire and technical/illegal
// blocks are excluded, since those either cannot fall or must never enter the world this way.
// Every drop is spawned as a falling block entity, so nothing just hovers in the air. Landed
// blocks are tracked by GameEvents for the arena reset.
class AblockalypseModifier(game: Game) : GameModifier(game) {
    companion object : ModifierCompanion<AblockalypseModifier>("ablockalypse", ::AblockalypseModifier)

    override val info: GameModifierInfo = modifierInfo

    private val intervalSecs = ModifierConfigs.int("ablockalypse", "interval", 15)
    private val startDelaySecs = ModifierConfigs.int("ablockalypse", "start-delay", 10)
    private val perDrop = ModifierConfigs.int("ablockalypse", "per-drop", 30)
    private val size = ModifierConfigs.int("ablockalypse", "size", 75)

    // Blocks that must never rain down: they are not real placeable blocks (air variants,
    // technical states like moving pistons), are fluids, are otherwise special/custom-only items
    // (TNT is a special drop, shulker boxes and pistons are undesirable), or are illegal/
    // unobtainable blocks that must never enter the world through spawning (command blocks,
    // barriers, bedrock, ...).
    private val EXCLUDED = setOf(
        Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
        Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN,
        Material.FIRE, Material.SOUL_FIRE,
        Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY,
        Material.MOVING_PISTON, Material.PISTON_HEAD,
        Material.PISTON, Material.STICKY_PISTON, Material.TNT,
    ) + Game.UNOBTAINABLE_ITEMS

    // Computed once: every block material of this Minecraft version minus the exclusions above
    // (shulker boxes included, since they would just trap players' inventories on landing).
    private val materials: List<Material> = Material.values().filter {
        it.isBlock && it !in EXCLUDED && !it.name.endsWith("_SHULKER_BOX")
    }

    // Placed in small batches per tick so a burst never hits the server with a full round of block
    // updates at once.
    private val blocksPerTick = 10
    private val pending = java.util.ArrayDeque<BlockPos>()

    // In-flight falling blocks: removed at game end. Landed ones die on their own and are tracked
    // by GameEvents.onEntityChangeBlock for the arena reset.
    private val fallingBlocks = mutableListOf<org.bukkit.entity.FallingBlock>()

    override fun tick(tick: Ticking.Tick) {
        for (i in 0 until blocksPerTick) {
            val pos = pending.pollFirst() ?: break
            val falling = game.world.spawnFallingBlock(
                Location(game.world, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5),
                materials.random().createBlockData(),
            )
            falling.setHurtEntities(false)
            falling.dropItem = false
            fallingBlocks += falling
        }

        if (!tick.isInInterval(game.anchorTick() + startDelaySecs * 20, intervalSecs * 20)) return

        val area = game.playArea(size)
        repeat(perDrop) {
            pending.addLast(BlockPos(
                (area.minX..area.maxX).random(),
                game.world.maxHeight - 1,
                (area.minZ..area.maxZ).random(),
            ))
        }

        game.players.forEach { p ->
            p.sendActionBar(p.locale().component("modifier.ablockalypse.warning", color = NamedTextColor.RED))
        }
        game.players.playSoundSafe(Sound.ENTITY_WITHER_BREAK_BLOCK, 1.0f, 0.5f)
    }

    override fun onEnd() {
        pending.clear()
        // Remove anything still in flight: landed blocks are already dead and remove() on them
        // is a no-op.
        fallingBlocks.removeAll { !it.isValid }
        fallingBlocks.forEach { it.remove() }
        fallingBlocks.clear()
    }
}
