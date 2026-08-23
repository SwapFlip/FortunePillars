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
import com.swapflip.fortunepillars.player.SpectatorManager
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.Cosmetics
import com.swapflip.fortunepillars.util.FeatureToggle
import com.swapflip.fortunepillars.util.Hooks
import com.swapflip.fortunepillars.util.PlayerStats
import com.swapflip.fortunepillars.util.QueueMethod
import com.swapflip.fortunepillars.util.chatComponent
import com.swapflip.fortunepillars.util.trackToFastStats
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID

object Commands {
    // All players currently waiting across every per-map queue in QueueManager (the new per-map-queue API).
    private fun allQueuedPlayers(): List<Player> =
        QueueManager.availableMaps().mapNotNull { QueueManager.queueForMap(it.name) }.flatMap { it.players }

    // Default map for callers that used to add a player to the single global queue without naming a
    // map (e.g. COMMAND-join fallback, admin add). Picks the first available map, or "" to let
    // QueueManager fall back to the player's last map / AUTO default.
    private fun defaultQueueMap(): String = QueueManager.availableMaps().firstOrNull()?.name ?: ""

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
        commandMap.register("fortunepillars", WrappedCommand("pof", "Fortune Pillars viewer commands. Sub-command: spectate.", emptyList(), pof))
        commandMap.register("fortunepillars", WrappedCommand("pp-config", "Manage the FortunePillars configuration.", listOf("pillar-peril-config", "pp-settings"), ppConfig))
    }

    // Unregisters the commands so a plugin reload (PlugMan) does not leave ghost commands bound to
    // the previous classloader/instance.
    fun unregister() {
        val commandMap = Bukkit.getCommandMap()
        listOf("game", "pp", "pof", "pp-config").forEach { name ->
            commandMap.getCommand(name)?.unregister(commandMap)
        }
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

                    if (players.isEmpty())
                        return send(sender, locale.component("games.start.no_players", color = NamedTextColor.RED))

                    // onlyAlive=false: eliminated players (spectators) are still part of their running
                    // game - starting them in another one would double-book them in both.
                    if (players.any { GameManager.isInGame(it, onlyAlive = false) })
                        return send(sender, locale.component("games.start.player_in_game", color = NamedTextColor.RED))

                    // Queued players are waiting in the plugin world - starting them in another
                    // game would leave them double-booked in the queue.
                    if (players.any { QueueManager.currentQueueOf(it) != null })
                        return send(sender, locale.component("games.start.player_in_queue", color = NamedTextColor.RED))

                    if (mode !in Registry.modes)
                        return send(sender, locale.component("games.start.invalid_mode", color = NamedTextColor.RED))

                    if (center == null)
                        return send(sender, locale.component("games.start.invalid_position", color = NamedTextColor.RED))

                    if (world == null)
                        return send(sender, locale.component("games.start.invalid_world", color = NamedTextColor.RED))

                    val id = Game.generateId()
                    runCatching {
                        // Manual starts run without modifiers; the queue flow applies the voted type.
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
                        return send(sender, locale.chatComponent("commands.games.none", color = NamedTextColor.YELLOW))

                    sender.sendMessage(locale.chatComponent("commands.games.list"))
                    for (game in GameManager.games.values) {
                        val accentColor = game.info.accentColor()
                        sender.sendMessage(component("==== ", NamedTextColor.DARK_GRAY).append(component(game.id, accentColor)).append(component(" ====", NamedTextColor.DARK_GRAY)))
                        sender.sendMessage(locale.chatComponent("commands.games.players", "${game.players.size}/${game.initialPlayers.size}", color = NamedTextColor.GRAY).color(accentColor))
                        sender.sendMessage(locale.chatComponent("commands.games.item_countdown", game.itemCountdown.toString()).color(accentColor))
                        sender.sendMessage(locale.chatComponent("commands.games.time_left", game.timeLeft.preciselyFormatted, color = NamedTextColor.GRAY).color(accentColor))
                        sender.sendMessage(locale.chatComponent("commands.games.mode", game.info.namespace, color = NamedTextColor.GRAY).color(accentColor))
                        sender.sendMessage(locale.chatComponent("commands.games.center", game.center.toString()).color(accentColor))
                    }
                    true
                }
                "info" -> {
                    if (!sender.hasPermission("fortunepillars.info"))
                        return noPermission(sender)

                    val game = GameManager[args.getOrNull(1) ?: return gameHelp(sender)]
                        ?: return send(sender, locale.component("games.wrong_id", color = NamedTextColor.RED))

                    val accent = game.info.accentColor().asHexString()
                    send(sender, locale.chatComponent("commands.games.info.title"))
                    send(sender, miniMessage("<dark_gray>========================"))
                    send(sender, miniMessage(locale.string("commands.games.info.game_id", accent, game.id)))
                    send(sender, miniMessage(locale.string("commands.games.info.players", accent, game.players.size.toString(), game.initialPlayers.size.toString())))
                    send(sender, locale.chatComponent("commands.games.info.status"))
                    send(sender, miniMessage("<dark_gray>========================"))
                    send(sender, miniMessage(locale.string("commands.games.info.mode", game.info.name(sender.locale()))))
                    send(sender, miniMessage(locale.string("commands.games.info.mode_color", accent)))
                    send(sender, miniMessage(locale.string("commands.games.info.generator", game.info.vertGen().toString())))
                    send(sender, miniMessage(locale.string("commands.games.info.item_countdown", game.info.itemCountdown().toString())))
                    send(sender, miniMessage("<dark_gray>========================"))
                    send(sender, locale.chatComponent("commands.games.info.players_header"))
                    game.initialPlayers.forEach { p ->
                        send(sender, miniMessage(locale.string("commands.games.info.player_line", if (p in game.players) "green" else "red", p.name())))
                    }
                    send(sender, miniMessage("<dark_gray>========================"))
                }
                else -> gameHelp(sender)
            }
        }

        private fun gameHelp(sender: CommandSender): Boolean {
            val locale = sender.locale()
            sender.sendMessage(miniMessage("<bold><gradient:#71CCF8:#FC91EC:#F87171>Fortune Pillars</gradient></bold> <gray>v${FortunePillars.VERSION}"))
            sender.sendMessage(locale.chatComponent("commands.games.help.start", color = NamedTextColor.GRAY))
            sender.sendMessage(locale.chatComponent("commands.games.help.stop", color = NamedTextColor.GRAY))
            sender.sendMessage(locale.chatComponent("commands.games.help.list", color = NamedTextColor.GRAY))
            sender.sendMessage(locale.chatComponent("commands.games.help.info", color = NamedTextColor.GRAY))
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
                3 -> mutableListOf() // <mode> <x,y,z>: positions have no meaningful completion
                4 -> if (args[0] == "start") Bukkit.getWorlds().map { it.name }.filter { it.startsWith(args[3]) }.toMutableList() else mutableListOf()
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

                    if (QueueManager.currentQueueOf(sender) != null)
                        return send(sender, locale.component("queue.join.already", color = NamedTextColor.YELLOW))

                    // Pick the arena before joining: the map menu places the player into the queue on selection.
                    if (!QueueEvents.openMapMenu(sender)) {
                        QueueManager.joinMap(sender, defaultQueueMap())
                        send(sender, locale.component("queue.join.success", color = NamedTextColor.GREEN))
                    }
                    true
                }
                "leave" -> {
                    if (sender !is Player || Configuration.queueMethod != QueueMethod.COMMAND)
                        return false

                    if (QueueManager.currentQueueOf(sender) == null)
                        return send(sender, locale.component("queue.leave.not_queued", color = NamedTextColor.RED))

                    QueueManager.leaveQueue(sender)
                    send(sender, locale.component("queue.leave.success", color = NamedTextColor.YELLOW))
                }
                "admin" -> {
                    if (!sender.hasPermission("fortunepillars.queue.admin"))
                        return noPermission(sender)

                    when (args.getOrNull(1)) {
                        "list" -> {
                            if (allQueuedPlayers().isEmpty())
                                return send(sender, locale.component("queue.list.empty", color = NamedTextColor.GREEN))

                            send(sender, locale.component("queue.list.list", allQueuedPlayers().size.toString(), color = NamedTextColor.GREEN))
                            for (player in allQueuedPlayers())
                                sender.sendMessage(component("| - ", NamedTextColor.GRAY).append(player.displayName().color(NamedTextColor.WHITE)))
                            true
                        }
                            "add", "remove" -> {
                            val players = resolvePlayers(sender, args.drop(2).joinToString(" "))
                            // A typo'd or offline name resolves to nobody: warn instead of silently
                            // reporting success with zero players added.
                            val tokens = args.drop(2).flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
                            val unresolved = tokens.filter { !it.startsWith("@") && Bukkit.getPlayerExact(it) == null }
                            if (unresolved.isNotEmpty())
                                sender.sendMessage(sender.locale().component("queue.add.unresolved", unresolved.joinToString(", "), color = NamedTextColor.RED))

                            val inQueue = players.filter { QueueManager.currentQueueOf(it) != null }

                            if (args[1] == "add") {
                                if (players.isEmpty())
                                    return send(sender, locale.component("queue.add.empty", color = NamedTextColor.RED))

                                if (inQueue.size == players.size && players.isNotEmpty())
                                    return send(sender, locale.component("queue.add.already", color = NamedTextColor.YELLOW))

                                players.forEach { QueueManager.joinMap(it, defaultQueueMap()) }
                                send(sender, locale.component("queue.add.success", color = NamedTextColor.GREEN))
                            } else {
                                if (inQueue.isEmpty())
                                    return send(sender, locale.component("queue.remove.not_queued", color = NamedTextColor.RED))

                                inQueue.forEach { QueueManager.leaveQueue(it) }
                                send(sender, locale.component("queue.remove.success", color = NamedTextColor.YELLOW))
                            }
                        }
                        "clear" -> {
                            if (allQueuedPlayers().isEmpty())
                                return send(sender, locale.component("queue.clear.empty", color = NamedTextColor.YELLOW))

                            allQueuedPlayers().forEach { QueueManager.leaveQueue(it) }
                            send(sender, locale.component("queue.clear.success", color = NamedTextColor.YELLOW))
                        }
                        else -> false
                    }
                }
                else -> {
                    sender.sendMessage(locale.chatComponent("commands.queue.status", allQueuedPlayers().size.toString(), Configuration.queueMinPlayers.toString(), Configuration.queueMode.gameInfo.namespace))
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

    // ======================== POF (SPECTATE) ========================

    internal val pof: PluginCommand = object : PluginCommand {
        override fun onCommand(sender: CommandSender, command: Command, label: String, rawArgs: Array<out String>): Boolean {
            val args = rawArgs.toList()
            val locale = sender.locale()
            return when (args.firstOrNull()) {
                "spectate" -> {
                    if (sender !is Player)
                        return send(sender, locale.component("pof.spectate.only_players", color = NamedTextColor.RED))
                    if (!sender.hasPermission("fortunepillars.spectate"))
                        return noPermission(sender)

                    val name = args.getOrNull(1)
                    if (name == null) {
                        sender.sendMessage(locale.chatComponent("commands.pof.help.spectate", color = NamedTextColor.GRAY))
                        return true
                    }

                    val arenaMap = MapManager.maps[name]
                        ?: return send(sender, locale.component("pof.spectate.invalid_map", name, color = NamedTextColor.RED))

                    // Teleporting a participant would eliminate them from a running match or
                    // break their queue state - map viewers must be free of both.
                    if (GameManager.isInGame(sender, onlyAlive = false) || QueueManager.currentQueueOf(sender) != null)
                        return send(sender, locale.component("pof.spectate.in_game", color = NamedTextColor.RED))

                    val world = Bukkit.getWorld(arenaMap.world)
                        ?: return send(sender, locale.component("pof.spectate.world_not_loaded", arenaMap.world, color = NamedTextColor.RED))

                    if (!SpectatorManager.start(sender, arenaMap.spectatorLocation(world)))
                        return send(sender, locale.component("pof.spectate.failed", color = NamedTextColor.RED))

                    send(sender, locale.component("pof.spectate.success", arenaMap.displayName ?: arenaMap.name, color = NamedTextColor.GREEN))
                }
                else -> {
                    sender.sendMessage(locale.chatComponent("commands.pof.help.spectate", color = NamedTextColor.GRAY))
                    true
                }
            }
        }

        override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
            return when (args.size) {
                1 -> listOf("spectate").filter { it.startsWith(args[0]) }.toMutableList()
                2 -> if (args[0] == "spectate") MapManager.maps.keys.filter { it.startsWith(args[1]) }.toMutableList() else mutableListOf()
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

                    send(sender, locale.component("config.reload", locale.string("config.reload.result.${result.name.lowercase()}"), color = NamedTextColor.YELLOW))
                    // The reload baseline is reset so a file that was edited while the server ran is
                    // not picked up again by the auto-reload watcher (it already is the current state).
                    Configuration.resetAutoReloadBaseline()
                    // Loot pools, modifiers and menu contents are re-read per game; queue-world and
                    // world-bound settings (queue.world, maps) only take effect for the next game.
                    send(sender, locale.component("config.reload.hint", color = NamedTextColor.GRAY))
                }
                "modify" -> modify(sender, locale, args.drop(1))
                else -> {
                    sender.sendMessage(locale.chatComponent("commands.config.help.reload", color = NamedTextColor.GRAY))
                    sender.sendMessage(locale.chatComponent("commands.config.help.get", color = NamedTextColor.GRAY))
                    sender.sendMessage(locale.chatComponent("commands.config.help.set", color = NamedTextColor.GRAY))
                    sender.sendMessage(locale.chatComponent("commands.config.help.add", color = NamedTextColor.GRAY))
                    sender.sendMessage(locale.chatComponent("commands.config.help.remove", color = NamedTextColor.GRAY))
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
                // The write is ours: don't let the auto-reload watcher treat it as an external edit.
                Configuration.resetAutoReloadBaseline()

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

            val list = Configuration.provider.getList(path)?.toMutableList()
                ?: return send(sender, locale.component("config.not_list", path, color = NamedTextColor.RED))
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
            // The write is ours: don't let the auto-reload watcher treat it as an external edit.
            Configuration.resetAutoReloadBaseline()

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
// First corner of a pending schematic selection, waiting for the second one. Entries expire
                    // after 10 minutes so a half-made selection can never leak a player into the map forever.
                    private val schematicSelections = mutableMapOf<UUID, Pair<BlockPos, Int>>()
                    private val selectionExpiryTicks = 10 * 60 * 20

                    // Drops every selection older than the expiry window. Only called when a new
                    // selection is started, so abandoned half-selections are swept eventually
                    // instead of accumulating forever.
                    private fun sweepExpiredSelections() {
                        val now = Bukkit.getCurrentTick()
                        schematicSelections.entries.removeAll { now - it.value.second >= selectionExpiryTicks }
                    }

        override fun onCommand(sender: CommandSender, command: Command, label: String, rawArgs: Array<out String>): Boolean {
            val args = rawArgs.toList()
            val locale = sender.locale()

            if (!sender.isOp && !sender.hasPermission("fortunepillars.map"))
                return noPermission(sender)

            return when (args.firstOrNull()) {
                "setup" -> {
                    if (sender !is Player)
                        return send(sender, locale.chatComponent("commands.map.only_setup", color = NamedTextColor.RED))

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
                        return send(sender, locale.chatComponent("commands.map.only_save", color = NamedTextColor.RED))

                    val arenaMap = MapManager.maps[args.getOrNull(1)]
                        ?: return send(sender, locale.component("map.not_existing", args.getOrNull(1) ?: "", color = NamedTextColor.RED))

                    if (sender.world.name != arenaMap.world)
                        return send(sender, locale.component("map.wrong_world", arenaMap.world, color = NamedTextColor.RED))

                    // Two-step selection: run the command at one corner, then walk to the opposite corner and run it again.
                    val first = schematicSelections[sender.uniqueId]
                        ?.takeIf { Bukkit.getCurrentTick() - it.second < selectionExpiryTicks }
                        ?.first
                        ?.also { schematicSelections.remove(sender.uniqueId) }
                    if (first == null) {
                        sweepExpiredSelections()
                        schematicSelections[sender.uniqueId] = BlockPos(sender.location.blockX, sender.location.blockY, sender.location.blockZ) to Bukkit.getCurrentTick()
                        return send(sender, locale.component("map.save.first", "${sender.location.blockX}/${sender.location.blockY}/${sender.location.blockZ}", color = NamedTextColor.YELLOW))
                    }

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
                        return send(sender, locale.chatComponent("commands.map.only_set", color = NamedTextColor.RED))

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

                    sender.sendMessage(locale.chatComponent("commands.map.list_header", color = NamedTextColor.GREEN))
                    MapManager.maps.values.forEach { m ->
                        sender.sendMessage(locale.chatComponent("commands.map.list_entry", m.name, m.spawns.size.toString(), m.world).color(NamedTextColor.GOLD))
                    }
                    true
                }
                "info" -> {
                    val arenaMap = MapManager.maps[args.getOrNull(1)]
                        ?: return send(sender, locale.component("map.not_existing", args.getOrNull(1) ?: "", color = NamedTextColor.RED))

                    sender.sendMessage(locale.chatComponent("commands.map.info.header", arenaMap.name, color = NamedTextColor.DARK_GRAY).color(NamedTextColor.GOLD))
                    sender.sendMessage(locale.chatComponent("commands.map.info.world", arenaMap.world, color = NamedTextColor.GRAY).color(NamedTextColor.WHITE))
                    sender.sendMessage(locale.chatComponent("commands.map.info.origin", "${arenaMap.origin.x}/${arenaMap.origin.y}/${arenaMap.origin.z}", color = NamedTextColor.GRAY).color(NamedTextColor.WHITE))
                    val hasSchematic = MapManager.schematicFile(arenaMap.name).isFile
                    sender.sendMessage(locale.chatComponent("commands.map.info.schematic", locale.string(if (hasSchematic) "commands.map.info.schematic_yes" else "commands.map.info.schematic_no")).color(if (hasSchematic) NamedTextColor.GREEN else NamedTextColor.RED))
                    arenaMap.spawns.forEachIndexed { i, sp -> sender.sendMessage(locale.chatComponent("commands.map.info.spawn", (i + 1).toString(), "${sp.x}/${sp.y}/${sp.z}").color(NamedTextColor.WHITE)) }
                    sender.sendMessage(locale.chatComponent("commands.map.info.spectator", arenaMap.spectatorSpawn?.let { "${it.x}/${it.y}/${it.z}" } ?: "-", color = NamedTextColor.GRAY).color(NamedTextColor.WHITE))
                    sender.sendMessage(locale.chatComponent("commands.map.info.death_height", arenaMap.deathHeight?.toString() ?: "-").color(NamedTextColor.WHITE))
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
                    sender.sendMessage(locale.chatComponent("commands.map.help.setup", color = NamedTextColor.GRAY))
                    sender.sendMessage(locale.chatComponent("commands.map.help.save", color = NamedTextColor.GRAY))
                    sender.sendMessage(locale.chatComponent("commands.map.help.paste", color = NamedTextColor.GRAY))
                    sender.sendMessage(locale.chatComponent("commands.map.help.set_spawn", color = NamedTextColor.GRAY))
                    sender.sendMessage(locale.chatComponent("commands.map.help.set_spectator", color = NamedTextColor.GRAY))
                    sender.sendMessage(locale.chatComponent("commands.map.help.set_deathheight", color = NamedTextColor.GRAY))
                    sender.sendMessage(locale.chatComponent("commands.map.help.overview", color = NamedTextColor.GRAY))
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

                    if (allQueuedPlayers().isEmpty())
                        return send(sender, locale.component("queue.forcestart.empty", color = NamedTextColor.RED))

                    val count = allQueuedPlayers().size
                    QueueManager.forceStart()
                    send(sender, locale.component("queue.forcestart.success", count.toString(), color = NamedTextColor.GREEN))
                }
                "leave" -> {
                    if (sender !is Player)
                        return false

                    val inQueue = QueueManager.currentQueueOf(sender) != null
                    val pillar = GameManager.player(sender, onlyAlive = false)

                    when {
                        inQueue -> {
                            QueueManager.leaveQueue(sender)
                            send(sender, locale.component("queue.leave.success", color = NamedTextColor.YELLOW))
                        }
                        pillar != null -> {
                            // Mid-game (or cage-phase) leave: eliminated like a quit, restored to the
                            // pre-queue state and sent home. eliminate()'s delayed spectator teleport
                            // skips players who already left the game world, so this never overrides
                            // the lobby send-back.
                            SpectatorManager.stop(sender)
                            pillar.game.eliminate(pillar)
                            runCatching { pillar.restore() }
                                .onFailure { pillar.game.error("Could not restore ${sender.name} after leaving the game.", it) }
                            val lobby = Configuration.getLobbySpawn()
                            sender.teleport(lobby)
                            sender.respawnLocation = lobby
                            send(sender, locale.component("queue.leave.success", color = NamedTextColor.YELLOW))
                        }
                        else -> send(sender, locale.component("queue.leave.not_in_game", color = NamedTextColor.RED))
                    }
                    true
                }
                "game" -> game.onCommand(sender, command, label, args.drop(1).toTypedArray())
                "queue" -> queue.onCommand(sender, command, label, args.drop(1).toTypedArray())
                "map" -> map.onCommand(sender, command, label, args.drop(1).toTypedArray())
                "config" -> ppConfig.onCommand(sender, command, label, args.drop(1).toTypedArray())
                "stats" -> statsCommand(sender, locale, args)
                "top" -> topCommand(sender, locale, args)
                "cosmetics" -> cosmeticsCommand(sender, locale, args)
                "on" -> toggleCommand(sender, locale, true)
                "off" -> toggleCommand(sender, locale, false)
                else -> false
            }
        }

        override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
            return when (args.size) {
                1 -> listOf("forcestart", "leave", "game", "queue", "map", "config", "stats", "top", "cosmetics", "on", "off").filter { it.startsWith(args[0]) }.toMutableList()
                else -> when (args.firstOrNull()) {
                    "game" -> game.onTabComplete(sender, command, alias, args.drop(1).toTypedArray()) ?: mutableListOf()
                    "queue" -> queue.onTabComplete(sender, command, alias, args.drop(1).toTypedArray()) ?: mutableListOf()
                    "map" -> map.onTabComplete(sender, command, alias, args.drop(1).toTypedArray()) ?: mutableListOf()
                    "config" -> ppConfig.onTabComplete(sender, command, alias, args.drop(1).toTypedArray()) ?: mutableListOf()
                    "top" -> listOf("wins", "losses", "kills", "deaths", "games", "streak").filter { it.startsWith(args.getOrNull(1) ?: "") }.toMutableList()
                    "cosmetics" -> Cosmetics.TRAILS.keys.filter { it.startsWith(args.getOrNull(1) ?: "") }.toMutableList()
                    else -> mutableListOf()
                }
            }
        }
    }

    // ======================== HELPERS ========================

    private fun noPermission(sender: CommandSender): Boolean {
        sender.sendMessage(sender.locale().chatComponent("commands.no_permission", color = NamedTextColor.RED))
        return true
    }

    private fun send(sender: CommandSender, component: net.kyori.adventure.text.Component): Boolean {
        sender.sendMessage(component)
        return true
    }

    // ======================== PLUGIN TOGGLE ========================

    // /pp on | /pp off - master switch (OP or console only). While off, only OP players may queue,
    // so an admin can keep testing the plugin while regular players are held out.
    private fun toggleCommand(sender: CommandSender, locale: Locale, enable: Boolean): Boolean {
        if (!sender.isOp && sender !is ConsoleCommandSender) return noPermission(sender)
        FeatureToggle.setEnabled(enable)
        val key = if (enable) "toggle.on" else "toggle.off"
        send(sender, locale.component(key, color = if (enable) NamedTextColor.GREEN else NamedTextColor.RED))
        // Let the other OPs know the state changed.
        Bukkit.getOnlinePlayers().filter { it.isOp && it != sender }.forEach { p ->
            p.sendMessage(p.locale().component(key))
        }
        return true
    }

    // ======================== STATS / LEADERBOARDS / COSMETICS ========================

    private fun statsCommand(sender: CommandSender, locale: Locale, args: List<String>): Boolean {
        val uuid = if (args.size >= 2) {
            val name = args[1]
            Bukkit.getPlayerExact(name)?.uniqueId ?: Bukkit.getOfflinePlayer(name).uniqueId
        } else if (sender is Player) {
            sender.uniqueId
        } else {
            return send(sender, locale.component("stats.usage", color = NamedTextColor.RED))
        }

        val d = PlayerStats.get(uuid)
        val name = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
        val games = d.gamesPlayed
        val rate = if (games > 0) d.wins * 100 / games else 0
        val rank = Hooks.rankName(uuid).ifBlank { locale.string("stats.no-rank") }
        sender.sendMessage(locale.component("stats.header", name, color = NamedTextColor.GREEN))
        sender.sendMessage(locale.component("stats.wins", d.wins.toString()))
        sender.sendMessage(locale.component("stats.losses", d.losses.toString()))
        sender.sendMessage(locale.component("stats.kills", d.kills.toString()))
        sender.sendMessage(locale.component("stats.deaths", d.deaths.toString()))
        sender.sendMessage(locale.component("stats.games", games.toString()))
        sender.sendMessage(locale.component("stats.winrate", rate.toString()))
        sender.sendMessage(locale.component("stats.streak", d.currentStreak.toString(), d.bestStreak.toString()))
        sender.sendMessage(locale.component("stats.rank", rank))
        sender.sendMessage(locale.component("stats.achievements", d.achievements.size.toString()))
        return true
    }

    private fun topCommand(sender: CommandSender, locale: Locale, args: List<String>): Boolean {
        val stat = (args.getOrNull(1) ?: "wins").lowercase()
        val valid = setOf("wins", "losses", "kills", "deaths", "games", "streak")
        if (stat !in valid) return send(sender, locale.component("top.invalid", color = NamedTextColor.RED))

        val list = PlayerStats.top(stat, Configuration.leaderboardSize)
        sender.sendMessage(locale.component("top.header", locale.string("stat.$stat"), color = NamedTextColor.GREEN))
        if (list.isEmpty()) {
            sender.sendMessage(locale.component("top.empty"))
            return true
        }
        list.forEachIndexed { i, (uuid, value) ->
            val name = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
            sender.sendMessage(locale.component("top.row", (i + 1).toString(), name, value.toString()))
        }
        return true
    }

    private fun cosmeticsCommand(sender: CommandSender, locale: Locale, args: List<String>): Boolean {
        if (sender !is Player) return false
        val uuid = sender.uniqueId
        val data = PlayerStats.get(uuid)

        if (args.size < 2) {
            sender.sendMessage(locale.component("cosmetics.list", color = NamedTextColor.AQUA))
            Cosmetics.TRAILS.forEach { (id, trail) ->
                val status = when {
                    data.activeCosmetic == id -> locale.string("cosmetics.active")
                    id in data.cosmetics -> locale.string("cosmetics.owned")
                    else -> locale.string("cosmetics.locked")
                }
                sender.sendMessage(locale.component("cosmetics.entry", locale.string(trail.nameKey), status))
            }
            sender.sendMessage(locale.component("cosmetics.hint"))
            return true
        }

        val id = args[1].lowercase()
        val trail = Cosmetics.TRAILS[id] ?: return send(sender, locale.component("cosmetics.unknown", color = NamedTextColor.RED))
        if (id !in data.cosmetics) return send(sender, locale.component("cosmetics.locked-msg", color = NamedTextColor.RED))
        PlayerStats.setActiveCosmetic(uuid, id)
        PlayerStats.saveAll()
        return send(sender, locale.component("cosmetics.selected", locale.string(trail.nameKey), color = NamedTextColor.GREEN))
    }

    private fun parseBlockPos(raw: String): Location? {
        val parts = raw.split(Regex("[ ,]+")).map { it.trim() }.filter { it.isNotEmpty() }
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