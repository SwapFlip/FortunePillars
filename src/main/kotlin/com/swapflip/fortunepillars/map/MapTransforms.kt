package com.swapflip.fortunepillars.map

// Returns a copy of `map` whose origin is (0,0,0) and whose spawns/spectatorSpawn are shifted by
// -originalOrigin. This lets the schematic be pasted at (0,0,0) in a fresh world while every
// existing Game coordinate calculation (which reads map.origin / map.spawns) keeps working unchanged.
fun translateMapToOrigin(map: ArenaMap): ArenaMap {
    // NOTE: the field-by-field copy below must stay in sync with ArenaMap's properties. If a new
    // property is added to ArenaMap, it must be copied here (or it will be silently dropped).
    val o = map.origin
    val shift: (BlockPos) -> BlockPos = { BlockPos(it.x - o.x, it.y - o.y, it.z - o.z) }
    return ArenaMap(
        name = map.name,
        schematic = map.schematic,
        world = map.world,
        origin = BlockPos(0, 0, 0),
        spawns = map.spawns.mapTo(mutableListOf()) { shift(it) },
        spectatorSpawn = map.spectatorSpawn?.let(shift),
        deathHeight = map.deathHeight,
    ).also {
        it.displayName = map.displayName
        it.description = map.description
    }
}
