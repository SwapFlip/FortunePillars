package com.marcpg.pillarperil.map

import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.util.Configuration
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

object MapManager {
    private val folder: File get() = File(PillarPeril.PLUGIN.dataFolder, "maps")
    private val schematicFolder: File get() = File(PillarPeril.PLUGIN.dataFolder, "schematics")

    val maps = mutableMapOf<String, ArenaMap>()

    fun load() {
        maps.clear()
        folder.mkdirs()
        folder.listFiles { f -> f.isFile && f.extension == "yml" }?.forEach(::loadFile)
        PillarPeril.LOG.info("[Maps] Loaded ${maps.size} map(s).")
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

        val spectator = yaml.getIntegerList("spectator-spawn").let { if (it.size == 3) BlockPos(it[0], it[1], it[2]) else null }
        val deathHeight = if (yaml.contains("death-height")) yaml.getInt("death-height") else null

        maps[name] = ArenaMap(name, schematic, world, origin, spawns, spectator, deathHeight)
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
        yaml.save(File(folder, "${map.name}.yml"))
    }

    fun create(name: String, schematic: String, world: String, origin: BlockPos): ArenaMap? {
        if (name in maps) return null
        val map = ArenaMap(name, schematic, world, origin)
        maps[name] = map
        save(map)
        return map
    }

    fun delete(name: String) {
        maps.remove(name)
        File(folder, "$name.yml").delete()
    }

    fun schematicFile(name: String): File? {
        schematicFolder.mkdirs()
        return listOf(File(schematicFolder, "$name.schem"), File(schematicFolder, "$name.schematic"))
            .firstOrNull { it.isFile }
    }

    fun pickMap(playerCount: Int, world: World): ArenaMap? {
        val pool = if (Configuration.queueMapPool.isEmpty())
            maps.values
        else
            maps.values.filter { it.name in Configuration.queueMapPool }

        return pool.filter { it.world == world.name && it.spawns.size >= playerCount && it.spectatorSpawn != null }
            .shuffled()
            .firstOrNull()
    }
}
