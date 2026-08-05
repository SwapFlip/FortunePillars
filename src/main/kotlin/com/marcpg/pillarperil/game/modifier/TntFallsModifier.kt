package com.marcpg.pillarperil.game.modifier

import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameModifier
import com.marcpg.pillarperil.game.GameModifierCompanion
import com.marcpg.pillarperil.game.util.GameModifierInfo
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.playSoundSafe
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType

class TntFallsModifier(game: Game) : GameModifier(game) {
    companion object : GameModifierCompanion<TntFallsModifier> {
        override val modifierInfo: GameModifierInfo by lazy { GameModifierInfo(this, "tnt-falls") }

        override fun constructModifier(game: Game): TntFallsModifier = TntFallsModifier(game)
    }

    override val info: GameModifierInfo = modifierInfo

    private val fuse = Configuration.provider.getInt("modifiers.tnt-falls.fuse-ticks", 200)
    private val perDrop = Configuration.provider.getInt("modifiers.tnt-falls.per-drop", 3)

    private val spawned = mutableListOf<Entity>()

    private val tntType: EntityType by lazy {
        runCatching { EntityType.valueOf("PRIMED_TNT") }.getOrElse { EntityType.valueOf("TNT") }
    }

    private val fuseSetter: java.lang.reflect.Method? by lazy {
        val clazz = runCatching { Entity::class.java.classLoader.loadClass("org.bukkit.entity.PrimedTnt") }
            .getOrElse { runCatching { Entity::class.java.classLoader.loadClass("org.bukkit.entity.TNTPrimed") }.getOrNull() }
        clazz?.getMethod("setFuseTicks", Int::class.javaPrimitiveType)
    }

    override fun onItemCycle() {
        val bounds = game.arenaBounds ?: return

        game.players.playSoundSafe(Sound.ENTITY_TNT_PRIMED, 1.0f, 1.2f)

        runCatching {
            repeat(perDrop) {
                val x = (bounds.minX..bounds.maxX).random()
                val z = (bounds.minZ..bounds.maxZ).random()
                val location = Location(game.world, x + 0.5, (bounds.maxY + 10).toDouble(), z + 0.5)
                val entity = game.world.spawnEntity(location, tntType)
                runCatching { fuseSetter?.invoke(entity, fuse) }
                spawned += entity
            }
        }.onFailure {
            PillarPeril.LOG.error("[TntFalls] Could not spawn raining TNT.", it)
        }
    }

    override fun onEnd() {
        spawned.forEach { it.remove() }
        spawned.clear()
    }
}
