package com.swapflip.fortunepillars.event

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.FortunePillars
import com.swapflip.fortunepillars.game.util.Cage
import com.swapflip.fortunepillars.game.util.GameManager
import com.swapflip.fortunepillars.game.util.QueueManager
import com.swapflip.fortunepillars.map.ArenaMap
import com.swapflip.fortunepillars.map.MapManager
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.MINI_MESSAGE
import com.swapflip.fortunepillars.util.chatComponent
import com.swapflip.fortunepillars.util.setGlintOverride
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import java.util.Locale
import java.util.UUID

object QueueEvents : Listener {
    // Menu titles are configured (with MiniMessage color support) rather than hardcoded, so
    // servers can rename and restyle the pickers without touching the plugin.
    val voteTitle: Component get() = MINI_MESSAGE.deserialize(Configuration.menuVoteTitle)
    val mapTitle: Component get() = MINI_MESSAGE.deserialize(Configuration.menuMapTitle)
    val multiTitle: Component get() = MINI_MESSAGE.deserialize(Configuration.menuMultiTitle)
    private const val MAX_VISIBLE_MAPS = 14 // Slots available for maps in the 36-slot menu.
    // Players currently looking at the map menu: refreshes only touch these, instead of iterating
    // every online player every second.
    private val openMapMenus = mutableSetOf<UUID>()
    private val MAP_KEY = NamespacedKey(FortunePillars.PLUGIN, "map")

    private val leaving = mutableSetOf<Player>()

    fun isLeaving(player: Player): Boolean = player in leaving

    private val modeOrder = listOf("normal", "blocky", "action", "op")
    private val typeOrder = listOf(
        "normal", "lava-rises", "tnt-falls", "speedrun", "arrow-rain", "lightning",
        "moonwalk", "chain-swap",
        "ablockalypse", "lava-floor", "uhc", "mob-wave", "shrinking-world", "multi",
    )
    private val timeOrder = listOf(3, 5, 10, 15)

    private val modeMaterials = mapOf(
        "normal" to Material.DIAMOND_SWORD,
        "blocky" to Material.BRICKS,
        "action" to Material.FIREWORK_ROCKET,
        "op" to Material.NETHERITE_SWORD,
    )

    private val typeMaterials = mapOf(
        "normal" to Material.GREEN_WOOL,
        "lava-rises" to Material.LAVA_BUCKET,
        "tnt-falls" to Material.TNT,
        "speedrun" to Material.FEATHER,
        "arrow-rain" to Material.ARROW,
        "lightning" to Material.LIGHTNING_ROD,
        "moonwalk" to Material.GOLDEN_BOOTS,
        "chain-swap" to Material.ENDER_PEARL,
        "ablockalypse" to Material.COBBLESTONE,
        "lava-floor" to Material.MAGMA_BLOCK,
        "uhc" to Material.GOLDEN_APPLE,
        "mob-wave" to Material.ROTTEN_FLESH,
        "shrinking-world" to Material.RED_WOOL,
        "multi" to Material.NETHER_STAR,
    )

    private val timeMaterials = mapOf(
        3 to Material.LIME_DYE,
        5 to Material.LIGHT_BLUE_DYE,
        10 to Material.PURPLE_DYE,
        15 to Material.RED_DYE,
    )

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (player !in QueueManager.queue) return
        if (event.hand != EquipmentSlot.HAND) return

        val item = event.item ?: return
        when (item.type) {
            Material.RED_DYE -> {
                event.isCancelled = true
                if (!leaving.add(player)) return

                player.sendActionBar(player.locale().component("queue.leave.confirm", color = NamedTextColor.RED))
                bukkitRunLater(60L) {
                    leaving.remove(player)
                    if (player.isOnline && player in QueueManager.queue) {
                        QueueManager.remove(player)
                        player.sendMessage(player.locale().chatComponent("queue.leave.success"))
                        player.teleport(Configuration.getLobbySpawn())
                    }
                }
            }
            Material.CHEST -> {
                event.isCancelled = true
                openVoteMenu(player)
            }
            else -> Unit
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val title = event.view.title()
        val player = event.whoClicked as? Player ?: return

        when (title) {
            voteTitle -> {
                event.isCancelled = true
                onVoteClick(player, event)
            }
            mapTitle -> {
                event.isCancelled = true
                onMapClick(player, event)
            }
            multiTitle -> {
                event.isCancelled = true
                onMultiClick(player, event)
            }
        }
    }

    // A drag over one of the picker menus would dump items into the slots: the pickers are
    // click-only, so any drag in them is cancelled outright.
    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.title() !in setOf(
                voteTitle,
                mapTitle,
                multiTitle,
            )
        ) return
        event.isCancelled = true
    }

    // Closing the multi modifier menu counts as confirming: the game can never get stuck waiting
    // for a player who closed their menu and never opens it again. The game's confirm is
    // idempotent, so confirming via the button and then closing still only counts once.
    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val title = event.view.title()
        // Track map-menu viewers so the per-second refresh only visits players who are actually
        // looking at it.
        if (title == mapTitle) {
            openMapMenus -= player.uniqueId
            return
        }
        if (title != multiTitle) return
        val pillar = GameManager.player(player) ?: return
        if (pillar.game.multiSelect && !pillar.game.multiSelectionDone)
            pillar.game.confirmMultiSelection(player)
    }

    private fun onVoteClick(player: Player, event: InventoryClickEvent) {
        if (QueueManager.votingLocked) {
            player.sendMessage(player.locale().chatComponent("queue.vote.locked", (QueueManager.countdownSecondsLeft ?: 0).toString()))
            return
        }

        val locale = player.locale()
        val voted = when (event.rawSlot) {
            10, 11, 12, 13, 14, 15 -> modeOrder.getOrNull(event.rawSlot - 10)?.also { QueueManager.recordVote(player, mode = it) }?.let { locale.string("game.$it.name") }
            19, 20, 21, 22, 23, 24, 25 -> typeOrder.getOrNull(event.rawSlot - 19)?.also { QueueManager.recordVote(player, type = it) }?.let { locale.string("modifier.$it.name") }
            28, 29, 30, 31, 32, 33, 34 -> typeOrder.getOrNull(event.rawSlot - 21)?.also { QueueManager.recordVote(player, type = it) }?.let { locale.string("modifier.$it.name") }
            37, 38, 39, 40 -> timeOrder.getOrNull(event.rawSlot - 37)?.also { QueueManager.recordVote(player, time = it) }?.let { "${it} ${locale.string("vote.time.seconds")}" }
            // One click votes Random for mode, type, time and item loot.
            43 -> {
                QueueManager.recordVote(player, mode = QueueManager.Vote.RANDOM, type = QueueManager.Vote.RANDOM, time = QueueManager.Vote.RANDOM_TIME)
                locale.string("vote.random")
            }
            else -> null
        }
        if (voted != null) {
            player.sendActionBar(locale.chatComponent("vote.recorded", voted))
            // Only a real vote changes anything: an empty (border) click must not rebuild the
            // menus for everyone currently looking at them.
            refreshVoteMenus()
        }
    }

    private fun onMultiClick(player: Player, event: InventoryClickEvent) {
        val pillar = GameManager.player(player) ?: return
        val game = pillar.game
        if (!game.multiSelect || game.multiSelectionDone) return

        val item = event.currentItem ?: return

        if (item.type == Material.GREEN_DYE) {
            game.confirmMultiSelection(player)
            player.sendActionBar(player.locale().component("vote.multi.confirmed", color = NamedTextColor.GREEN))
            player.playSound(player, Sound.UI_BUTTON_CLICK, 1.0f, 1.5f)
            player.closeInventory()
            return
        }

        val name = item.itemMeta?.persistentDataContainer?.get(MAP_KEY, PersistentDataType.STRING) ?: return
        game.toggleMultiModifier(player, name)
        player.playSound(player, Sound.UI_BUTTON_CLICK, 1.0f, 1.5f)
        refreshMultiMenus()
        player.updateInventory()
    }

    private fun onMapClick(player: Player, event: InventoryClickEvent) {
        val item = event.currentItem ?: return
        if (item.type !in setOf(Material.SLIME_BALL, Material.FIRE_CHARGE)) return

        val name = item.itemMeta?.persistentDataContainer?.get(MAP_KEY, PersistentDataType.STRING) ?: return
        val map = MapManager.maps[name] ?: return
        QueueManager.recordVote(player, map = name)
        if (player !in QueueManager.queue) {
            QueueManager.add(player, map)
            player.sendMessage(player.locale().chatComponent("queue.join.success"))
        }
        player.playSound(player, Sound.UI_BUTTON_CLICK, 1.0f, 1.5f)
        player.closeInventory()
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (event.player in QueueManager.queue) event.isCancelled = true
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.player in QueueManager.queue || Cage.isProtected(event.block))
            event.isCancelled = true
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.player in QueueManager.queue) event.isCancelled = true
    }

    @EventHandler
    fun onBlockBurn(event: BlockBurnEvent) {
        if (Cage.isProtected(event.block)) {
            event.isCancelled = true
            return
        }
        // Fire (Igniter power-up, fire spread) burns flammable builds into permanent holes:
        // register the intact pre-burn state with the owning game so the arena reset restores it.
        // The bounds check is skipped on purpose, like for falling blocks: fire can climb towers
        // beyond the arena bounds, and an unregistered burn would survive into the next game.
        GameManager.getClosestGame(event.block.location, withinBounds = false)
            ?.buildings?.registerPlace(event.block.location)
    }

    // Destroys the survivors of the Cage protection filter: every block an explosion takes out is
    // registered with the owning game's buildings BEFORE it is destroyed, so the arena restores
    // itself with the next game instead of keeping permanent craters. (registerPlace stores the
    // block's current, intact state - which is exactly the state reset() should bring back.)
    private fun registerExploded(blocks: List<Block>) {
        blocks.forEach { block ->
            GameManager.games.values.firstOrNull { it.world == block.world && it.isWithin(block.location) }
                ?.buildings?.registerPlace(block.location)
        }
    }

    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeAll { Cage.isProtected(it) }
        registerExploded(event.blockList())
    }

    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeAll { Cage.isProtected(it) }
        registerExploded(event.blockList())
    }

    fun openMapMenu(player: Player): Boolean {
        val maps = QueueManager.mapVoteCandidates()
        if (maps.isEmpty()) return false

        val inv = Bukkit.createInventory(null, 36, mapTitle)
        border(inv, 36)
        fillMapMenu(inv, maps, player.locale())
        openMapMenus += player.uniqueId
        player.openInventory(inv)
        return true
    }

    // Rebuilds every open map menu, so the vote counts and "players playing" numbers shown while
    // someone is looking at the list stay up-to-date even as games start and finish. Only players
    // with an open map menu are visited.
    fun refreshMapMenus() {
        if (openMapMenus.isEmpty()) return
        val maps = QueueManager.mapVoteCandidates()
        if (maps.isEmpty()) return
        openMapMenus.forEach { uuid ->
            val player = Bukkit.getPlayer(uuid) ?: return@forEach
            if (player.openInventory.title() != mapTitle) return@forEach
            fillMapMenu(player.openInventory.topInventory, maps, player.locale())
        }
    }

    private fun fillMapMenu(inv: Inventory, maps: List<ArenaMap>, locale: Locale) {
        val leader = maps.maxByOrNull { QueueManager.mapVoteCounts()[it.name] ?: 0 }?.name
        // Clear the map slots first: when maps disappear from the pool (deleted, no schematic),
        // the stale item would otherwise keep sitting in the menu and still be clickable.
        for (slot in 10..16)
            inv.setItem(slot, null)
        for (slot in 19..25)
            inv.setItem(slot, null)
        maps.forEachIndexed { i, map ->
            if (i >= MAX_VISIBLE_MAPS) return@forEachIndexed
            val votes = QueueManager.mapVoteCounts()[map.name] ?: 0
            // Only mark the leader green if it actually has votes; otherwise every map stays grey.
            val isLeader = votes > 0 && map.name == leader
            inv.setItem((if (i < 7) 1 else 2) * 9 + 1 + i % 7, mapItem(map, votes, isLeader, locale))
        }
    }

    private fun openVoteMenu(player: Player) {
        val inv = Bukkit.createInventory(null, 54, voteTitle)
        border(inv, 54)
        fillMenu(inv, player.locale())
        player.openInventory(inv)
    }

    private fun refreshVoteMenus() {
        Bukkit.getOnlinePlayers().forEach { player ->
            if (player.openInventory.title() != voteTitle) return@forEach
            fillMenu(player.openInventory.topInventory, player.locale())
        }
    }

    // Opens the Multi Mode picker. Every player picks their own modifiers; any option
    // with at least one vote gets activated once everyone closed or confirmed the menu.
    fun openMultiMenu(player: Player) {
        val inv = Bukkit.createInventory(null, 45, multiTitle)
        border(inv, 45, bottomRow = false)
        fillMultiMenu(inv, player)
        player.openInventory(inv)
    }

// Rebuilds every open Multi Mode picker after someone toggles a modifier, so the vote counts stay
// live for everyone looking at their menu - not just for the player who clicked.
    private fun refreshMultiMenus() {
        Bukkit.getOnlinePlayers().forEach { player ->
            if (player.openInventory.title() != multiTitle) return@forEach
            fillMultiMenu(player.openInventory.topInventory, player)
        }
    }

    private fun fillMultiMenu(inv: Inventory, player: Player) {
        val pillar = GameManager.player(player) ?: return
        val selected = pillar.game.multiSelections[player.uniqueId] ?: emptySet()
        val locale = player.locale()

        typeOrder.filter { it != "multi" }.forEachIndexed { i, ns ->
            val votes = pillar.game.multiSelections.values.count { ns in it }
            val isSelected = ns in selected
            val item = ItemStack(if (isSelected) Material.GREEN_WOOL else Material.GRAY_WOOL)
            val meta = item.itemMeta
            meta.displayName(Component.text(locale.string("modifier.$ns.name"))
                .color(if (isSelected) NamedTextColor.GREEN else NamedTextColor.GOLD))
            meta.lore(listOf(
                Component.text(locale.string("modifier.$ns.description")).color(NamedTextColor.GRAY),
                Component.text(locale.string("vote.votes", votes.toString()))
                    .color(if (votes > 0) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY),
                Component.text(if (isSelected) locale.string("vote.multi.click.off") else locale.string("vote.multi.click.on"))
                    .color(NamedTextColor.DARK_GRAY),
            ))
            meta.persistentDataContainer.set(MAP_KEY, PersistentDataType.STRING, ns)
            if (isSelected)
                meta.setGlintOverride(true)
            item.itemMeta = meta
            // Rows of 7: slots 10-16, 19-25, ... (fits the remaining types without a bottom row).
            val row = i / 7
            val col = i % 7
            inv.setItem(10 + row * 9 + col, item)
        }

        inv.setItem(40, ItemStack(Material.GREEN_DYE).apply {
            val meta = itemMeta
            meta.displayName(Component.text(locale.string("vote.multi.done")).color(NamedTextColor.GREEN))
            meta.lore(listOf(Component.text(locale.string("vote.multi.done.description")).color(NamedTextColor.DARK_GRAY)))
            itemMeta = meta
        })
    }

    private fun fillMenu(inv: Inventory, locale: Locale) {
        modeOrder.forEachIndexed { i, ns ->
            val name = locale.string("game.$ns.name")
            inv.setItem(10 + i, voteItem(modeMaterials[ns] ?: Material.PAPER, name, QueueManager.modeVoteCounts()[ns] ?: 0, locale.string("vote.category.mode"), locale, null))
        }
        typeOrder.forEachIndexed { i, ns ->
            val customName = Configuration.modifierCustomNames[ns]
            val name = customName ?: locale.string("modifier.$ns.name")
            val customDesc = Configuration.modifierCustomDescriptions[ns]
            val desc = customDesc ?: (if (ns == "multi") locale.string("vote.multi.description") else locale.string("modifier.$ns.description"))
            inv.setItem(if (i < 7) 19 + i else 28 + (i - 7), voteItem(
                typeMaterials[ns] ?: Material.PAPER, name, QueueManager.typeVoteCounts()[ns] ?: 0,
                locale.string("vote.category.type"), locale, desc,
            ))
        }
        timeOrder.forEachIndexed { i, t ->
            val name = "$t ${locale.string("vote.time.seconds")}"
            inv.setItem(37 + i, voteItem(timeMaterials[t] ?: Material.PAPER, name, QueueManager.timeVoteCounts()[t] ?: 0, locale.string("vote.category.time"), locale, null))
        }
        // Bottom-right "Random" button: votes for random mode, type, time and item loot at once.
        val randomVotes = QueueManager.randomVoteCount()
        inv.setItem(43, ItemStack(Material.NETHER_STAR).apply {
            val meta = itemMeta
            meta.displayName(Component.text(locale.string("vote.random")).color(NamedTextColor.GOLD))
            meta.lore(listOf(
                Component.text(locale.string("vote.random.description")).color(NamedTextColor.DARK_GRAY),
                Component.text(locale.string("vote.votes", randomVotes.toString()))
                    .color(if (randomVotes > 0) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY),
            ))
            if (randomVotes > 0)
                meta.setGlintOverride(true)
            itemMeta = meta
        })
    }

    private fun border(inv: Inventory, size: Int, bottomRow: Boolean = true) {
        val pane = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            val meta = itemMeta
            meta.displayName(Component.text(" "))
            itemMeta = meta
        }
        for (slot in 0 until size) {
            val col = slot % 9
            val row = slot / 9
            val isEdge = col == 0 || col == 8 || row == 0 || (bottomRow && row == size / 9 - 1)
            if (isEdge) inv.setItem(slot, pane.clone())
        }
    }

    private fun voteItem(material: Material, name: String, votes: Int, category: String, locale: Locale, description: String?): ItemStack {
        val item = ItemStack(material)
        val meta: ItemMeta = item.itemMeta
        // Name and description support MiniMessage formatting (from config or locale)
        meta.displayName(MINI_MESSAGE.deserialize(name).color(NamedTextColor.GOLD))
        meta.lore(buildList {
            if (description != null)
                add(MINI_MESSAGE.deserialize(description).color(NamedTextColor.GRAY))
            add(Component.text(category).color(NamedTextColor.DARK_GRAY))
            add(Component.text(locale.string("vote.votes", votes.toString()))
                .color(if (votes > 0) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY))
        })
        // The current leader of the category glows, so the vote menu shows the state of the vote.
        if (votes > 0)
            meta.setGlintOverride(true)
        item.itemMeta = meta
        return item
    }

    private fun mapItem(map: ArenaMap, votes: Int, isLeader: Boolean, locale: Locale): ItemStack {
        // Custom material from config, else default (SLIME_BALL for leader, FIRE_CHARGE for others)
        val customMaterial = Configuration.mapCustomMaterials[map.name]?.let { Material.matchMaterial(it) }
        val material = customMaterial ?: (if (isLeader) Material.SLIME_BALL else Material.FIRE_CHARGE)
        val item = ItemStack(material)
        val meta: ItemMeta = item.itemMeta
        // Display name and description support MiniMessage colors (set in the map file via
        // /pp map set display-name / description, or edited in maps/<name>.yml).
        meta.displayName(MINI_MESSAGE.deserialize(map.displayName ?: map.name))
        meta.persistentDataContainer.set(MAP_KEY, PersistentDataType.STRING, map.name)
        val playersPlaying = GameManager.games.values
            .filter { it.map?.name == map.name }
            .sumOf { it.players.size }
        meta.lore(buildList {
            map.description?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { line ->
                add(MINI_MESSAGE.deserialize(line))
            }
            add(Component.text(locale.string("map.players.playing", playersPlaying.toString()))
                .color(if (playersPlaying > 0) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY))
            add(Component.text(locale.string("map.spawns.count", map.spawns.size.toString()))
                .color(NamedTextColor.DARK_GRAY))
        })
        item.itemMeta = meta
        return item
    }
}