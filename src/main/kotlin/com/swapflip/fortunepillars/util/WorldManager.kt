package com.swapflip.fortunepillars.util

import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.util.VoidChunkGenerator
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import java.io.File

object WorldManager {
    const val GAME_WORLD_PREFIX = "pillarperil_game_"

    fun gameWorldName(id: Int): String = "$GAME_WORLD_PREFIX$id"

    fun isGameWorld(world: World): Boolean = world.name.startsWith(GAME_WORLD_PREFIX)

    // Creates a fresh void world at (0,0,0) with autosave off. Returns null (and logs) on failure.
    // Returns null immediately when per-game-worlds is disabled so callers fall back to the shared world.
    fun createGameWorld(id: Int): World? {
        if (!Configuration.perGameWorlds) return null
        return runCatching {
            WorldCreator(gameWorldName(id))
                .generator(VoidChunkGenerator())
                .generateStructures(false)
                .keepSpawnInMemory(false)
                .createWorld()
                ?.apply { setAutoSave(false) }
        }.onFailure {
            FortunePillars.LOG.error("Could not create game world \"${gameWorldName(id)}\".", it)
        }.getOrNull()
    }

    // Unloads the world and asynchronously deletes its folder. No-op when deletion is disabled or the
    // world is not one we created.
    fun deleteGameWorld(world: World) {
        if (!Configuration.deleteGameWorldsOnCleanup) return
        if (!isGameWorld(world)) return
        val name = world.name
        runCatching { Bukkit.unloadWorld(world, false) }
            .onFailure { FortunePillars.LOG.warn("Could not unload world \"$name\".", it) }
        Bukkit.getScheduler().runTaskAsynchronously(FortunePillars.PLUGIN, Runnable {
            runCatching { File(Bukkit.getWorldContainer(), name).deleteRecursively() }
                .onFailure { FortunePillars.LOG.warn("Could not delete world folder \"$name\".", it) }
        })
    }
}
