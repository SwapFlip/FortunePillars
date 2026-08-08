package com.swapflip.fortunepillars

import com.marcpg.libpg.config.ConfigValueType
import com.marcpg.libpg.config.PaperConfigProvider
import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.marcpg.libpg.util.miniMessage
import com.swapflip.fortunepillars.event.QueueEvents
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.util.GameManager
import com.swapflip.fortunepillars.game.util.QueueManager
import com.swapflip.fortunepillars.map.BlockPos
import com.swapflip.fortunepillars.map.MapManager
import com.swapflip.fortunepillars.map.MapPaster
import com.swapflip.fortunepillars.map.SchematicReader
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.QueueMethod
import com.swapflip.fortunepillars.util.trackToFastStats
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

object Commands {
    internal interface PluginCommand : CommandExecutor, TabCompleter

    private class WrappedCommand(
        name: String,
        description: String,
        aliases: List<String>,
        private val handler: PluginCommand,
    ) : Command(name, description, "/$name", aliases) {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean =
            handler.onCommand(sender, this, commandLabel, args)

        override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> =
            handler.onTabComplete(sender, this, alias, args) ?: mutableListOf()
    }

    fun register() {
        val commandMap = Bukkit.getCommandMap()
        commandMap.register("fortunepillars", WrappedCommand("game", "Utilities for managing Fortune Pillars games or starting new ones.", listOf("pillar-peril", "match", "round"), game))
        commandMap.register("fortunepillars", WrappedCommand("pp", "Fortune Pillars root command. Sub-commands: game, queue, map, config.", emptyList(), pp))
        commandMap.register("fortunepillars", WrappedCommand("pp-config", "Manage the FortunePillars configuration.", listOf("pillar-peril-config", "pp-settings"), ppConfig))
    }

    // ======================== GAME ========================

    internal val game: PluginCommand = object : PluginCommand {
        override fun onCommand(sender: CommandSender, command: Command, label: String, rawArgs: Array<out String>): Boolean {
            val args = rawArgs.toList()
            val locale = sender.locale()
            if (args.isEmpty())
                return gameHelp(sender)

            return when (args.first()) {
                "start" -> {
                    if (!sender.hasPermission("fortunepillars.start"))
                        return noPermission(sender)

                    if (args.size < 5)
                        return gameHelp(sender)

                    val mode = args[1]
                    val center = parseBlockPos(args[2])
                    val world = Bukkit.getWorld(args[3])
                    val players = resolvePlayers(sender, args.drop(4).joinToString(" "))

                    if (players.any { GameManager.player(it) != null })
                        return send(sender, locale.component("games.start.player_in_game", color = NamedTextColor.RED))

                    if (mode !in Registry.modes)
                        return send(sender, locale.component("games.start.invalid_mode", color = NamedTextColor.RED))

                    if (center == null || world == null)
                        return send(sender, locale.component("games.start.invalid_mode", color = NamedTextColor.RED))

                    val id = Game.generateId()
                    runCatching {
                        // TODO: Supply list of modifiers here:
                        Registry.modes[mode]!!.constructGame(id, center.toLocation(world), players, listOf()).init()
                    }.onFailure {
                        FortunePillars.LOG.error("Could not start game", it)
                        it.trackToFastStats()
                        return send(sender, locale.component("games.start.internal_error", color = NamedTextColor.RED))
                    }
                    send(sender, locale.component("games.start.success", id, color = NamedTextColor.GREEN))
                }
                "stop" -> {
                    if (!sender.hasPermission("fortunepillars.stop"))
                        return noPermission(sender)

                    val game = GameManager[args.getOrNull(1) ?: return gameHelp(sender)]
                        ?: return send(sender, locale.component("games.wrong_id", color = NamedTextColor.RED))

                    game.end(Game.EndingCause.FORCE)
                    send(sender, locale.component("games.stop.success", game.id, color = NamedTextColor.YELLOW))
                }
                "list" -> {
                    if (!sender.hasPermission("fortunepillars.list"))
                        return noPermission(sender)

                    if (args.getOrNull(1) == "raw") {
                        if (GameManager.games.isEmpty())
                            return send(sender, component("empty"))
                        return send(sender, component(GameManager.games.keys.joinToString(";")))
                    }

                    if (GameManager.games.isEmpty())
                        return send(sender, component("There are no games running.", NamedTextColor.YELLOW))

                    sender.sendMessage(component("Running games:"))
                    for (game in GameManager.games.values) {
                        val accentColor = game.info.accentColor()
                        sender.sendMessage(component("==== ", NamedTextColor.DARK_GRAY).append(component(game.id, accentColor)).append(component(" ====", NamedTextColor.DARK_GRAY)))
                        sender.sendMessage(component("> Players: ", NamedTextColor.GRAY).append(component("${game.players.size}/${game.initialPlayers.size}", accentColor)))
                        sender.sendMessage(component("> Item Countdown: ", NamedTextColor.GRAY).append(component(game.itemCountdown.toString(), accentColor)))
                        sender.sendMessage(component("> Time Left: ", NamedTextColor.GRAY).append(component(game.timeLeft.preciselyFormatted, accentColor)))
                        sender.sendMessage(component("> Mode: ", NamedTextColor.GRAY).append(component(game.info.namespace, accentColor)))
                        sender.sendMessage(component("> Center Location: ", NamedTextColor.GRAY).append(component(game.center.toString(), accentColor)))
                    }
                    true
                }
                "info" -> {
                    if (!sender.hasPermission("fortunepillars.info"))
                        return noPermission(sender)

                    val game = GameManager[args.getOrNull(1) ?: return gameHelp(sender)]
                        ?: return send(sender, locale.component("games.wrong_id", color = NamedTextColor.RED))

                    val accentColor = game.info.accentColor()
                    send(sender, miniMessage("""
                        <bold><gradient:#71CCF8:#FC91EC:#F87171>Fortune Pillars</gradient></bold>
                        <dark_gray>========================
                        <dark_gray>Game ID: <${game.info.accentColor().asHexString()}>${game.id}
                        <dark_gray>Players: <${game.info.accentColor().asHexString()}>${game.players.size}<dark_gray>/<${game.info.accentColor().asHexString()}>${game.initialPlayers.size}
                        <dark_gray>Status: <green>In-Game
                        <dark_gray>========================
                        <dark_gray>Game Mode: ${game.info.name(sender.locale())}
                        <dark_gray>Mode Color: <${game.info.accentColor().asHexString()}>${game.info.accentColor().asHexString()}
                        <dark_gray>Mode Generator: <yellow>${game.info.vertGen()}
                        <dark_gray>Mode Item Countdown: <yellow>${game.info.itemCountdown()}
                        <dark_gray>========================
                        <dark_gray>Players:
                        ${game.initialPlayers.joinToString { "<dark_gray>| <${if (it in game.players) "green" else "red"}>${it.name()}" }}
                        <dark_gray>========================
                    """.trimIndent()))
                }
                else -> gameHelp(sender)
            }
        }

        private fun gameHelp(sender: CommandSender): Boolean {
            sender.sendMessage(miniMessage("<bold><gradient:#71CCF8:#FC91EC:#F87171>Fortune Pillars</gradient></bold> <gray>v${FortunePillars.VERSION}"))
            sender.sendMessage(component("> /game start <mode> <center> <world> <players> — start a new game.", NamedTextColor.GRAY))
            sender.sendMessage(component("> /game stop <id> — stop a running game.", NamedTextColor.GRAY))
            sender.sendMessage(component("> /game list [raw] — list all running games.", NamedTextColor.GRAY))
            sender.sendMessage(component("> /game info <id> — get information about a game.", NamedTextColor.GRAY))
            return true
        }

        override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
            return when (args.size) {
                1 -> listOf("start", "stop", "list", "info").filter { it.startsWith(args[0]) }.toMutableList()
                2 -> when (args[0]) {
                    "start" -> Registry.modes.keys.filter { it.startsWith(args[1]) }.toMutableList()
                    "stop", "info" -> GameManager.games.keys.filter { it.startsWith(args[1]) }.toMutableList()
                    else -> mutableListOf()
                }
                3 -> if (args[0] == "start") Bukkit.getWorlds().map { it.name }.filter { it.startsWith(args[2]) }.toMutableList() else mutableListOf()
                else -> mutableListOf()
            }
        }
    }

    // ======================== QUEUE ========================

    internal val queue: PluginCommand = object : PluginCommand {
        override fun onCommand(sender: CommandSender, command: Command, label: String, rawArgs: Array<out String>): Boolean {
            val args = rawArgs.toList()
            val locale = sender.locale()

            if (!Configuration.queueEnabled) {
                sender.sendMessage(locale.component("queue.disabled", color = NamedTextColor.RED))
                return true
            }

            return when (args.firstOrNull()) {
                "join" -> {
                    if (sender !is Player || Configuration.queueMethod != QueueMethod.COMMAND)
                        return false

                    if (sender in QueueManager.queue)
                        return send(sender, locale.component("queue.join.already", color = NamedTextColor.YELLOW))

                    // Pick the arena before joining: the map menu places the player into the queue on selection.
                    if (!QueueEvents.openMapMenu(sender)) {
                        QueueManager.add(sender)
                        send(sender, locale.component("queue.join.success", color = NamedTextColor.GREEN))
                    }
                    true
                }
                "leave" -> {
                    if (sender !is Player || Configuration.queueMethod != QueueMethod.COMMAND)
                        return false

                    if (sender !in QueueManager.queue)
                        return send(sender, locale.component("queue.leave.not_queued", color = NamedTextColor.RED))

                    QueueManager.remove(sender)
                    send(sender, locale.component("queue.leave.success", color = NamedTextColor.YELLOW))
                }
                "admin" -> {
                    if (!sender.isOp)
                        return false

                    when (args.getOrNull(1)) {
                        "list" -> {
                            if (QueueManager.queue.isEmpty())
                                return send(sender, locale.component("queue.list.empty", color = NamedTextColor.GREEN))

                            send(sender, locale.component("queue.list.list", QueueManager.queue.size.toString(), color = NamedTextColor.GREEN))
                            for (player in QueueManager.queue)
                                sender.sendMessage(component("| - ", NamedTextColor.GRAY).append(player.displayName().color(NamedTextColor.WHITE)))
                            true
                        }
                        "add", "remove" -> {
                            val players = resolvePlayers(sender, args.drop(2).joinToString(" "))
                            val inQueue = players.filter { it in QueueManager.queue }

                            if (args[1] == "add") {
                                if (inQueue.size == players.size && players.isNotEmpty())
                                    return send(sender, locale.component("queue.add.already", color = NamedTextColor.YELLOW))

                                players.forEach { QueueManager.add(it) }
                                send(sender, locale.component("queue.add.success", color = NamedTextColor.GREEN))
                            } else {
                                if (inQueue.isEmpty())
                                    return send(sender, locale.component("queue.remove.not_queued", color = NamedTextColor.RED))

                                inQueue.forEach { QueueManager.remove(it) }
                                send(sender, locale.component("queue.remove.success", color = NamedTextColor.YELLOW))
                            }
                        }
                        "clear" -> {
                            if (QueueManager.queue.isEmpty())
                                return send(sender, locale.component("queue.clear.empty", color = NamedTextColor.YELLOW))

                            QueueManager.queue.toList().forEach { QueueManager.remove(it) }
                            send(sender, locale.component("queue.clear.success", color = NamedTextColor.YELLOW))
                        }
                        else -> false
                    }
                }
                else -> {
                    sender.sendMessage(component("Queue: ${QueueManager.queue.size}/${Configuration.queueMinPlayers} players (mode: ${Configuration.queueMode.gameInfo.namespace}). Use /pp queue join to join.", NamedTextColor.GRAY))
                    true
                }
            }
        }

        override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
            if (!Configuration.queueEnabled)
                return mutableListOf()

            return when (args.size) {
                1 -> listOf("join", "leave", "admin").filter { it.startsWith(args[0]) }.toMutableList()
                2 -> if (args[0] == "admin") listOf("list", "add", "remove", "clear").filter { it.startsWith(args[1]) }.toMutableList() else mutableListOf()
                else -> mutableListOf()
            }
        }
    }

    // ======================== PP CONFIG ========================

    internal val ppConfig: PluginCommand = object : PluginCommand {
        override fun onCommand(sender: CommandSender, command: Command, label: String, rawArgs: Array<out String>): Boolean {
            val args = rawArgs.toList()
            val locale = sender.locale()
            if (!sender.hasPermission("fortunepillars.config"))
                return noPermission(sender)

            return when (args.firstOrNull()) {
                "reload" -> {
                    val result = Configuration.loadChecking()

                    result.second.forEach { sender.sendMessage(component(it)) }
                    send(sender, locale.component("config.reload", locale.string("config.reload.result.${result.first.name.lowercase()}"), color = NamedTextColor.YELLOW))
                }
                "modify" -> modify(sender, locale, args.drop(1))
                else -> {
                    sender.sendMessage(component("> /pp-config reload", NamedTextColor.GRAY))
                    sender.sendMessage(component("> /pp-config modify <path> get", NamedTextColor.GRAY))
                    sender.sendMessage(component("> /pp-config modify <path> set <value>", NamedTextColor.GRAY))
                    sender.sendMessage(component("> /pp-config modify <path> add <value>", NamedTextColor.GRAY))
                    sender.sendMessage(component("> /pp-config modify <path> remove <value>", NamedTextColor.GRAY))
                    true
                }
            }
        }

        private fun modify(sender: CommandSender, locale: java.util.Locale, args: List<String>): Boolean {
            val path = args.getOrNull(0) ?: return false
            val operation = args.getOrNull(1) ?: return false
            val value = args.drop(2).joinToString(" ")

            return when (operation) {
                "get" -> {
                    val obj = (Configuration.provider as PaperConfigProvider).configuration.get(path)
                    when {
                        obj is List<*> -> send(sender, locale.component("config.get.list", path, color = NamedTextColor.YELLOW)
                            .append(component(obj.joinToString("\n- ", "\n- ") { it.toString() })))

                        obj != null -> send(sender, locale.component("config.get.object", path, obj.toString(), color = NamedTextColor.YELLOW))

                        else -> send(sender, locale.component("config.key_not_existing", path, color = NamedTextColor.RED))
                    }
                }
                "set" -> modifySet(sender, locale, path, value)
                "add", "remove" -> modifyList(sender, locale, path, value, operation == "add")
                else -> false
            }
        }

        private fun modifySet(sender: CommandSender, locale: java.util.Locale, path: String, value: String): Boolean {
            return runCatching {
                when (Configuration.provider.approximatePathType(path)) {
                    ConfigValueType.STRING -> Configuration.provider.setString(path, value)
                    ConfigValueType.INT -> Configuration.provider.setInt(path, value.toInt())
                    ConfigValueType.LONG -> Configuration.provider.setLong(path, value.toLong())
                    ConfigValueType.DOUBLE -> Configuration.provider.setDouble(path, value.toDouble())
                    ConfigValueType.BOOLEAN -> Configuration.provider.setBoolean(path, value.toBoolean())
                    ConfigValueType.LIST, ConfigValueType.MAP -> error("list/map")
                    else -> error("unknown")
                }

                runCatching {
                    Configuration.save()
                }.onFailure {
                    it.trackToFastStats()
                    error("save")
                }

                if (path == "queue.enabled" && value == "true")
                    sender.sendMessage(locale.component("config.set.note.queue", color = NamedTextColor.RED))

                locale.component("config.set.confirm", path, value, color = NamedTextColor.YELLOW)
            }.getOrElse {
                when (it.message) {
                    "list/map" -> locale.component("config.set.section_list", color = NamedTextColor.RED)
                    "save" -> locale.component("config.error", color = NamedTextColor.RED)
                    else -> locale.component("config.set.invalid", path, value, color = NamedTextColor.RED)
                }
            }.let { send(sender, it) }
        }

        private fun modifyList(sender: CommandSender, locale: java.util.Locale, path: String, value: String, add: Boolean): Boolean {
            if (Configuration.provider.approximatePathType(path) != ConfigValueType.LIST)
                return send(sender, locale.component("config.not_list", path, color = NamedTextColor.RED))

            val list = Configuration.provider.getList(path)!!.toMutableList()
            if (!add && value !in list)
                return send(sender, locale.component("config.remove.not_containing", value, path, color = NamedTextColor.RED))

            if (add) list.add(value) else list.remove(value)
            Configuration.provider.setList(path, list)

            runCatching {
                Configuration.save()
            }.onFailure {
                it.trackToFastStats()
                return send(sender, locale.component("config.error", color = NamedTextColor.RED))
            }

            return send(sender, locale.component("config.${if (add) "add" else "remove"}.confirm", value, path, color = NamedTextColor.YELLOW))
        }

        override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
            return when (args.size) {
                1 -> listOf("reload", "modify").filter { it.startsWith(args[0]) }.toMutableList()
                2 -> if (args[0] == "modify") Configuration.getEntries().map { it.key }.filter { it.startsWith(args[1]) }.toMutableList() else mutableListOf()
                3 -> if (args[0] == "modify") listOf("get", "set", "add", "remove").filter { it.startsWith(args[2]) }.toMutableList() else mutableListOf()
                else -> mutableListOf()
            }
        }
    }

    // ======================== PP ROOT ========================

    internal val map: PluginCommand = object : PluginCommand {
        // First corner of a pending schematic selection, waiting for the second one.
        private val schematicSelections = mutableMapOf<UUID, BlockPos>()

        override fun onCommand(sender: CommandSender, command: Command, label: String, rawArgs: Array<out String>): Boolean {
            val args = rawArgs.toList()
            val locale = sender.locale()

            if (!sender.isOp && !sender.hasPermission("fortunepillars.map"))
                return noPermission(sender)

            return when (args.firstOrNull()) {
                "setup" -> {
                    if (sender !is Player)
                        return send(sender, component("Only players can set up maps.", NamedTextColor.RED))

                    val name = args.getOrNull(1) ?: return false
                    if (name in MapManager.maps)
                        return send(sender, locale.component("map.already_exists", name, color = NamedTextColor.RED))

                    val origin = BlockPos(sender.location.blockX, sender.location.blockY, sender.location.blockZ)
                    val map = MapManager.create(name, sender.world.name, origin)
                        ?: return send(sender, locale.component("map.already_exists", name, color = NamedTextColor.RED))

                    send(sender, locale.component("map.setup.success", name, "${origin.x}/${origin.y}/${origin.z}", color = NamedTextColor.GREEN))
                }
                "save" -> {
                    if (sender !is Player)
                        return send(sender, component("Only players can save maps.", NamedTextColor.RED))

                    val arenaMap = MapManager.maps[args.getOrNull(1)]
                        ?: return send(sender, locale.component("map.not_existing", args.getOrNull(1) ?: "", color = NamedTextColor.RED))

                    if (sender.world.name != arenaMap.world)
                        return send(sender, locale.component("map.wrong_world", arenaMap.world, color = NamedTextColor.RED))

                    // Two-step selection: run the command at one corner, then walk to the opposite corner and run it again.
                    val first = schematicSelections[sender.uniqueId]
                    if (first == null) {
                        schematicSelections[sender.uniqueId] = BlockPos(sender.location.blockX, sender.location.blockY, sender.location.blockZ)
                        return send(sender, locale.component("map.save.first", "${sender.location.blockX}/${sender.location.blockY}/${sender.location.blockZ}", color = NamedTextColor.YELLOW))
                    }

                    schematicSelections.remove(sender.uniqueId)
                    val second = BlockPos(sender.location.blockX, sender.location.blockY, sender.location.blockZ)
                    val saved = MapManager.saveSchematic(arenaMap, sender.world, first, second)
                        ?: return send(sender, locale.component("map.save.failed", arenaMap.name, color = NamedTextColor.RED))

                    send(sender, locale.component("map.save.success", arenaMap.name, saved.width.toString(), saved.height.toString(), saved.length.toString(), saved.blocks.toString(), color = NamedTextColor.GREEN))
                }
                "paste" -> {
                    val name = args.getOrNull(1) ?: return false
                    val arenaMap = MapManager.maps[name]
                        ?: return send(sender, locale.component("map.not_existing", name, color = NamedTextColor.RED))

                    val world = Bukkit.getWorld(arenaMap.world)
                        ?: return send(sender, locale.component("map.world_not_loaded", arenaMap.world, color = NamedTextColor.RED))

                    val file = MapManager.schematicFile(name)
                    if (!file.isFile)
                        return send(sender, locale.component("map.no_schematic", name, color = NamedTextColor.RED))

                    val schematic = SchematicReader.read(file)
                        ?: return send(sender, locale.component("map.read_failed", name, color = NamedTextColor.RED))

                    val bounds = MapPaster.paste(schematic, world, arenaMap.origin)
                    send(sender, locale.component("map.paste.success", name, "${bounds.maxX - bounds.minX + 1}", "${bounds.maxY - bounds.minY + 1}", "${bounds.maxZ - bounds.minZ + 1}", color = NamedTextColor.GREEN))
                }
                "set" -> {
                    if (sender !is Player)
                        return send(sender, component("Only players can set map locations.", NamedTextColor.RED))

                    when (args.getOrNull(1)) {
                        "spawn" -> {
                            val n = args.getOrNull(2)?.toIntOrNull() ?: return false
                            if (n < 1)
                                return send(sender, locale.component("map.invalid_spawn_number", color = NamedTextColor.RED))

                            val arenaMap = MapManager.maps[args.getOrNull(3)]
                                ?: return send(sender, locale.component("map.not_existing", args.getOrNull(3) ?: "", color = NamedTextColor.RED))

                            if (sender.world.name != arenaMap.world)
                                return send(sender, locale.component("map.wrong_world", arenaMap.world, color = NamedTextColor.RED))

                            // Spawns must be set in order; skipping one leaves a (0,0,0) placeholder
                            // that would cage two players together during a game.
                            if (n > arenaMap.spawns.size + 1)
                                return send(sender, locale.component("map.spawns_out_of_order", color = NamedTextColor.RED))

                            val pos = BlockPos(sender.location.blockX, sender.location.blockY, sender.location.blockZ)
                            while (arenaMap.spawns.size < n)
                                arenaMap.spawns.add(pos)
                            arenaMap.spawns[n - 1] = pos
                            MapManager.save(arenaMap)
                            send(sender, locale.component("map.set.spawn.success", n.toString(), arenaMap.name, "${pos.x}/${pos.y}/${pos.z}", color = NamedTextColor.GREEN))
                        }
                        "spectatorspawn" -> {
                            val arenaMap = MapManager.maps[args.getOrNull(2)]
                                ?: return send(sender, locale.component("map.not_existing", args.getOrNull(2) ?: "", color = NamedTextColor.RED))

                            if (sender.world.name != arenaMap.world)
                                return send(sender, locale.component("map.wrong_world", arenaMap.world, color = NamedTextColor.RED))

                            arenaMap.spectatorSpawn = BlockPos(sender.location.blockX, sender.location.blockY, sender.location.blockZ)
                            MapManager.save(arenaMap)
                            send(sender, locale.component("map.set.spectator.success", arenaMap.name, color = NamedTextColor.GREEN))
                        }
                        "deathheight" -> {
                            val arenaMap = MapManager.maps[args.getOrNull(2)]
                                ?: return send(sender, locale.component("map.not_existing", args.getOrNull(2) ?: "", color = NamedTextColor.RED))

                            if (sender.world.name != arenaMap.world)
                                return send(sender, locale.component("map.wrong_world", arenaMap.world, color = NamedTextColor.RED))

                            arenaMap.deathHeight = sender.location.blockY
                            MapManager.save(arenaMap)
                            send(sender, locale.component("map.set.deathheight.success", arenaMap.name, sender.location.blockY.toString(), color = NamedTextColor.GREEN))
                        }
                        else -> false
                    }
                }
                "list" -> {
                    if (MapManager.maps.isEmpty())
                        return send(sender, locale.component("map.list.empty", color = NamedTextColor.YELLOW))

                    sender.sendMessage(component("Maps:", NamedTextColor.GREEN))
                    MapManager.maps.values.forEach { m ->
                        sender.sendMessage(component("> ", NamedTextColor.GRAY)
                            .append(component(m.name, NamedTextColor.GOLD))
                            .append(component(" (${m.spawns.size} spawns, world: ${m.world})", NamedTextColor.DARK_GRAY)))
                    }
                    true
                }
                "info" -> {
                    val arenaMap = MapManager.maps[args.getOrNull(1)]
                        ?: return send(sender, locale.component("map.not_existing", args.getOrNull(1) ?: "", color = NamedTextColor.RED))

                    sender.sendMessage(component("==== ", NamedTextColor.DARK_GRAY).append(component(arenaMap.name, NamedTextColor.GOLD)).append(component(" ====", NamedTextColor.DARK_GRAY)))
                    sender.sendMessage(component("> World: ", NamedTextColor.GRAY).append(component(arenaMap.world, NamedTextColor.WHITE)))
                    sender.sendMessage(component("> Origin: ", NamedTextColor.GRAY).append(component("${arenaMap.origin.x}/${arenaMap.origin.y}/${arenaMap.origin.z}", NamedTextColor.WHITE)))
                    sender.sendMessage(component("> Saved Schematic: ", NamedTextColor.GRAY).append(component(if (MapManager.schematicFile(arenaMap.name).isFile) "yes" else "no - run /pp map save <name>", if (MapManager.schematicFile(arenaMap.name).isFile) NamedTextColor.GREEN else NamedTextColor.RED)))
                    arenaMap.spawns.forEachIndexed { i, s -> sender.sendMessage(component("> Spawn ${i + 1}: ", NamedTextColor.GRAY).append(component("${s.x}/${s.y}/${s.z}", NamedTextColor.WHITE))) }
                    sender.sendMessage(component("> Spectator: ", NamedTextColor.GRAY).append(component(arenaMap.spectatorSpawn?.let { "${it.x}/${it.y}/${it.z}" } ?: "-", NamedTextColor.WHITE)))
                    sender.sendMessage(component("> Death Height: ", NamedTextColor.GRAY).append(component(arenaMap.deathHeight?.toString() ?: "-", NamedTextColor.WHITE)))
                    true
                }
                "delete" -> {
                    val name = args.getOrNull(1) ?: return false
                    if (name !in MapManager.maps)
                        return send(sender, locale.component("map.not_existing", name, color = NamedTextColor.RED))

                    MapManager.delete(name)
                    send(sender, locale.component("map.delete.success", name, color = NamedTextColor.YELLOW))
                }
                "reset" -> {
                    val arenaMap = MapManager.maps[args.getOrNull(1)]
                        ?: return send(sender, locale.component("map.not_existing", args.getOrNull(1) ?: "", color = NamedTextColor.RED))

                    arenaMap.spawns.clear()
                    arenaMap.spectatorSpawn = null
                    arenaMap.deathHeight = null
                    MapManager.save(arenaMap)
                    send(sender, locale.component("map.reset.success", arenaMap.name, color = NamedTextColor.YELLOW))
                }
                else -> {
                    sender.sendMessage(component("> /pp map setup <name> — register the area around your position as a map.", NamedTextColor.GRAY))
                    sender.sendMessage(component("> /pp map save <name> — select the arena with 2 corners: run the command at one corner, then at the opposite one.", NamedTextColor.GRAY))
                    sender.sendMessage(component("> /pp map paste <name> — re-paste the saved arena for planning.", NamedTextColor.GRAY))
                    sender.sendMessage(component("> /pp map set spawn <n> <name> — set spawn n at your position.", NamedTextColor.GRAY))
                    sender.sendMessage(component("> /pp map set spectatorspawn <name> — set the spectator camera spot.", NamedTextColor.GRAY))
                    sender.sendMessage(component("> /pp map set deathheight <name> — set the void kill height.", NamedTextColor.GRAY))
                    sender.sendMessage(component("> /pp map list | info <name> | delete <name> | reset <name>", NamedTextColor.GRAY))
                    true
                }
            }
        }

        override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
            return when (args.size) {
                1 -> listOf("setup", "save", "paste", "set", "list", "info", "delete", "reset").filter { it.startsWith(args[0]) }.toMutableList()
                2 -> when (args[0]) {
                    "save", "paste", "info", "delete", "reset" -> MapManager.maps.keys.filter { it.startsWith(args[1]) }.toMutableList()
                    "set" -> listOf("spawn", "spectatorspawn", "deathheight").filter { it.startsWith(args[1]) }.toMutableList()
                    else -> mutableListOf()
                }
                3 -> if (args[0] == "set" && args[1] != "spawn") MapManager.maps.keys.filter { it.startsWith(args[2]) }.toMutableList() else mutableListOf()
                4 -> if (args[0] == "set" && args[1] == "spawn") MapManager.maps.keys.filter { it.startsWith(args[3]) }.toMutableList() else mutableListOf()
                else -> mutableListOf()
            }
        }
    }

    internal val pp: PluginCommand = object : PluginCommand {
        override fun onCommand(sender: CommandSender, command: Command, label: String, rawArgs: Array<out String>): Boolean {
            val args = rawArgs.toList()
            val locale = sender.locale()
            return when (args.firstOrNull()) {
                "forcestart" -> {
                    if (!sender.isOp && !sender.hasPermission("fortunepillars.forcestart"))
                        return noPermission(sender)

                    if (QueueManager.queue.isEmpty())
                        return send(sender, locale.component("queue.forcestart.empty", color = NamedTextColor.RED))

                    val count = QueueManager.queue.size
                    QueueManager.forceStart()
                    send(sender, locale.component("queue.forcestart.success", count.toString(), color = NamedTextColor.GREEN))
                }
                "game" -> game.onCommand(sender, command, label, args.drop(1).toTypedArray())
                "queue" -> queue.onCommand(sender, command, label, args.drop(1).toTypedArray())
                "map" -> map.onCommand(sender, command, label, args.drop(1).toTypedArray())
                "config" -> ppConfig.onCommand(sender, command, label, args.drop(1).toTypedArray())
                else -> false
            }
        }

        override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
            return when (args.size) {
                1 -> listOf("forcestart", "game", "queue", "map", "config").filter { it.startsWith(args[0]) }.toMutableList()
                else -> when (args.firstOrNull()) {
                    "game" -> game.onTabComplete(sender, command, alias, args.drop(1).toTypedArray()) ?: mutableListOf()
                    "queue" -> queue.onTabComplete(sender, command, alias, args.drop(1).toTypedArray()) ?: mutableListOf()
                    "map" -> map.onTabComplete(sender, command, alias, args.drop(1).toTypedArray()) ?: mutableListOf()
                    "config" -> ppConfig.onTabComplete(sender, command, alias, args.drop(1).toTypedArray()) ?: mutableListOf()
                    else -> mutableListOf()
                }
            }
        }
    }

    // ======================== HELPERS ========================

    private fun noPermission(sender: CommandSender): Boolean {
        sender.sendMessage(component("You don't have permission to use this command!", NamedTextColor.RED))
        return true
    }

    private fun send(sender: CommandSender, component: net.kyori.adventure.text.Component): Boolean {
        sender.sendMessage(component)
        return true
    }

    private fun parseBlockPos(raw: String): Location? {
        val parts = raw.split(" ", ",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 3) return null

        val x = parts[0].toDoubleOrNull() ?: return null
        val z = parts[2].toDoubleOrNull() ?: return null
        return Location(null, x, parts[1].toDoubleOrNull() ?: return null, z)
    }

    private fun Location.toLocation(world: World) = Location(world, x, y, z)

    private fun resolvePlayers(sender: CommandSender, raw: String): List<Player> {
        val fromSelector = runCatching { Bukkit.selectEntities(sender, raw).mapNotNull { it as? Player } }.getOrDefault(listOf())
        if (fromSelector.isNotEmpty() || raw.startsWith("@"))
            return fromSelector

        return raw.split(",").mapNotNull { Bukkit.getPlayerExact(it.trim()) }
    }
}