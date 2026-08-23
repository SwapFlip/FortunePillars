package com.swapflip.fortunepillars.map

import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.util.Configuration
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.math.max
import kotlin.math.min

object MapManager {
    private val folder: File get() = File(FortunePillars.PLUGIN.dataFolder, "maps")

    // Pre-rename installs kept their data in the "PillarPeril" folder: fall back to it so maps
    // (and their schematics) keep working without re-adding everything under the new name.
    private val legacyFolder: File get() = File(FortunePillars.PLUGIN.dataFolder.parentFile, "PillarPeril/maps")

    val maps = mutableMapOf<String, ArenaMap>()

    fun load() {
        maps.clear()
        folder.mkdirs()
        folder.listFiles { f -> f.isFile && f.extension == "yml" }?.forEach(::loadFile)
        // New maps always save into the current folder, so they never split across both locations.
        if (maps.isEmpty())
            legacyFolder.listFiles { f -> f.isFile && f.extension == "yml" }?.forEach(::loadFile)
        FortunePillars.LOG.info("[Maps] Loaded ${maps.size} map(s).")
    }

    private fun loadFile(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val name = yaml.getString("name") ?: file.nameWithoutExtension
        val schematic = yaml.getString("schematic") ?: return
        val world = yaml.getString("world") ?: return
        val origin = yaml.getIntegerList("origin").let { if (it.size == 3) BlockPos(it[0], it[1], it[2]) else return }

        val spawns = yaml.getMapList("spawns").mapNotNull { m ->
            val x = m["x"] as? Int ?: return@mapNotNull null
            val y = m["y"] as? Int ?: return@mapNotNull null
            val z = m["z"] as? Int ?: return@mapNotNull null
            BlockPos(x, y, z)
        }.toMutableList()

        // Strip (0,0,0) placeholders (created when spawn N was set before spawns 1..N-1) and
        // de-duplicate, keeping ONE copy of each real spawn. A typo'd duplicate like "[A, A, B]"
        // must shrink capacity by one, not silently drop both A's.
        spawns.removeAll { it == BlockPos(0, 0, 0) }
        val seen = mutableSetOf<BlockPos>()
        spawns.removeAll { !seen.add(it) }

        val spectator = yaml.getIntegerList("spectator-spawn").let { if (it.size == 3) BlockPos(it[0], it[1], it[2]) else null }
        val deathHeight = if (yaml.contains("death-height")) yaml.getInt("death-height") else null

        maps[name] = ArenaMap(name, schematic, world, origin, spawns, spectator, deathHeight).also {
            it.displayName = yaml.getString("display-name")
            it.description = yaml.getString("description")
        }
    }

    fun save(map: ArenaMap) {
        folder.mkdirs()
        val yaml = YamlConfiguration()
        yaml.set("name", map.name)
        yaml.set("schematic", map.schematic)
        yaml.set("world", map.world)
        yaml.set("origin", listOf(map.origin.x, map.origin.y, map.origin.z))
        yaml.set("spawns", map.spawns.map { mapOf("x" to it.x, "y" to it.y, "z" to it.z) })
        map.spectatorSpawn?.let { yaml.set("spectator-spawn", listOf(it.x, it.y, it.z)) }
        map.deathHeight?.let { yaml.set("death-height", it) }
        map.displayName?.let { yaml.set("display-name", it) }
        map.description?.let { yaml.set("description", it) }
        yaml.save(File(folder, "${map.name}.yml"))
    }

    fun create(name: String, world: String, origin: BlockPos): ArenaMap? {
        if (name in maps) return null
        val map = ArenaMap(name, "$name.schem", world, origin)
        maps[name] = map
        save(map)
        return map
    }

    fun delete(name: String) {
        maps.remove(name)
        File(folder, "$name.yml").delete()
        File(folder, "$name.schem").delete()
        invalidateSchematicCache(name)
    }

    fun schematicFile(name: String): File {
        val current = File(folder, "$name.schem")
        return if (current.exists()) current else File(legacyFolder, "$name.schem")
    }

    // Schematic existence is checked on every queue tick, map-menu refresh and map pick: cache
    // the result per map and invalidate whenever a schematic is saved or deleted. Files rarely
    // appear or vanish on their own, so a stale entry for the lifetime of a session is acceptable.
    private val schematicExists = mutableMapOf<String, Boolean>()

    fun hasSchematic(name: String): Boolean = schematicExists.getOrPut(name) { schematicFile(name).exists() }

    fun invalidateSchematicCache(name: String? = null) {
        if (name == null) schematicExists.clear()
        else schematicExists.remove(name)
    }

    // Saves the schematic between the two selected corners and re-anchors the map's origin to the selection's
    // min corner. The paste anchor then always matches the schematic's local (0,0,0), so the arena is never offset.
    fun saveSchematic(map: ArenaMap, world: World, first: BlockPos, second: BlockPos): SavedSchematic? {
        val origin = BlockPos(
            min(first.x, second.x),
            min(first.y, second.y),
            min(first.z, second.z),
        )
        map.origin = origin
        save(map)
        return SchematicSaver.save(world, first, second, schematicFile(map.name)).also {
            // The schematic either now exists or was replaced - drop the stale cache entry.
            invalidateSchematicCache(map.name)
        }
    }

    fun pickMap(playerCount: Int, world: World, exclude: String? = null): ArenaMap? {
        val pool = if (Configuration.queueMapPool.isEmpty())
            maps.values
        else
            maps.values.filter { it.name in Configuration.queueMapPool }

        val candidates = pool.filter {
            it.world == world.name && it.spawns.size >= playerCount && it.spectatorSpawn != null && hasSchematic(it.name)
        }
        val filtered = if (exclude != null) candidates.filter { it.name != exclude } else candidates

        return (filtered.ifEmpty { candidates }).shuffled().firstOrNull()
    }
}
