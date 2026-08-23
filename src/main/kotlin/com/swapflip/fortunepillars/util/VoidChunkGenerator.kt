package com.swapflip.fortunepillars.util

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import java.util.Random

class VoidChunkGenerator : ChunkGenerator() {
    override fun generateNoise(worldInfo: org.bukkit.generator.WorldInfo, random: Random, x: Int, z: Int, chunkData: ChunkGenerator.ChunkData) {
        // No terrain at all, just air.
    }

    override fun getFixedSpawnLocation(world: World, random: Random): Location = Location(world, 0.5, 128.0, 0.5)
}
