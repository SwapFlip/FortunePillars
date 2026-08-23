package com.swapflip.fortunepillars.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MapTransformsTest {
    @Test
    fun `translate shifts spawns by minus origin and zeroes origin`() {
        val map = ArenaMap(
            name = "test",
            schematic = "test",
            world = "world",
            origin = BlockPos(100, 64, 100),
            spawns = mutableListOf(BlockPos(102, 64, 100), BlockPos(100, 64, 105)),
            spectatorSpawn = BlockPos(110, 70, 110),
            deathHeight = 0,
        )
        val t = translateMapToOrigin(map)
        assertEquals(BlockPos(0, 0, 0), t.origin)
        assertEquals(BlockPos(2, 0, 0), t.spawns[0])
        assertEquals(BlockPos(0, 0, 5), t.spawns[1])
        assertEquals(BlockPos(10, 6, 10), t.spectatorSpawn)
        assertEquals(map.name, t.name)
    }
}
