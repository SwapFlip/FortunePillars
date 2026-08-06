package com.marcpg.pillarperil.event

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.game.util.Cage
import com.marcpg.pillarperil.game.util.QueueManager
import com.marcpg.pillarperil.map.ArenaMap
import com.marcpg.pillarperil.map.MapManager
import com.marcpg.pillarperil.util.Configuration
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import java.util.Locale

object QueueEvents : Listener {
    private const val VOTE_TITLE = "Vote for Game Mode"
    private const val MAP_TITLE = "Select a Map"
    private val MAP_KEY = NamespacedKey(PillarPeril.PLUGIN, "map")

    private val leaving = mutableSetOf<Player>()

    private val modeOrder = listOf("weak", "normal", "balanced", "op", "shuffle", "swapper")
    private val typeOrder = listOf("normal", "lava-rises", "tnt-falls", "speedrunner")
    private val timeOrder = listOf(3, 5, 10, 15)

    private val modeMaterials = mapOf(
        "weak" to Material.WOODEN_SWORD,
        "normal" to Material.DIAMOND_SWORD,
        "balanced" to Material.GOLDEN_APPLE,
        "op" to Material.NETHERITE_SWORD,
        "shuffle" to Material.SHULKER_BOX,
        "swapper" to Material.ENDER_PEARL,
    )

    private val typeMaterials = mapOf(
        "normal" to Material.GREEN_WOOL,
        "lava-rises" to Material.LAVA_BUCKET,
        "tnt-falls" to Material.TNT,
        "speedrunner" to Material.FEATHER,
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

                player.sendMessage(component("Leaving the queue in 3 seconds...", NamedTextColor.RED))
                bukkitRunLater(60L) {
                    leaving.remove(player)
                    if (player.isOnline && player in QueueManager.queue) {
                        QueueManager.remove(player)
                        player.sendMessage(component("You left the queue.", NamedTextColor.RED))
                        player.teleport(Configuration.getSpawnLocation(player.world))
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
            Component.text(VOTE_TITLE) -> {
                event.isCancelled = true
                onVoteClick(player, event)
            }
            Component.text(MAP_TITLE) -> {
                event.isCancelled = true
                onMapClick(player, event)
            }
        }
    }

    private fun onVoteClick(player: Player, event: InventoryClickEvent) {
        if (QueueManager.votingLocked) {
            player.sendMessage(player.locale().component("queue.vote.locked", (QueueManager.countdownSecondsLeft ?: 0).toString(), color = NamedTextColor.RED))
            return
        }

        when (event.rawSlot) {
            10, 11, 12, 13, 14, 15, 16 -> modeOrder.getOrNull(event.rawSlot - 10)?.let { QueueManager.recordVote(player, mode = it) }
            19, 20, 21, 22, 23, 24, 25 -> typeOrder.getOrNull(event.rawSlot - 19)?.let { QueueManager.recordVote(player, type = it) }
            28, 29, 30, 31, 32, 33, 34 -> timeOrder.getOrNull(event.rawSlot - 28)?.let { QueueManager.recordVote(player, time = it) }
        }

        refreshVoteMenus()
    }

    private fun onMapClick(player: Player, event: InventoryClickEvent) {
        val item = event.currentItem ?: return
        if (item.type !in setOf(Material.SLIME_BALL, Material.FIRE_CHARGE)) return

        val name = item.itemMeta?.persistentDataContainer?.get(MAP_KEY, PersistentDataType.STRING) ?: return
        val map = MapManager.maps[name] ?: return
        QueueManager.recordVote(player, map = name)
        if (player !in QueueManager.queue) {
            QueueManager.add(player, map)
            player.sendMessage(player.locale().component("queue.join.success", color = NamedTextColor.GREEN))
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
        if (Cage.isProtected(event.block)) event.isCancelled = true
    }

    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeAll { Cage.isProtected(it) }
    }

    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeAll { Cage.isProtected(it) }
    }

    fun openMapMenu(player: Player): Boolean {
        val maps = QueueManager.mapVoteCandidates()
        if (maps.isEmpty()) return false

        val inv = Bukkit.createInventory(null, 27, Component.text(MAP_TITLE))
        border(inv, 27)

        val leader = maps.maxByOrNull { QueueManager.mapVoteCounts()[it.name] ?: 0 }?.name
        maps.forEachIndexed { i, map ->
            if (i >= 7) return@forEachIndexed
            val votes = QueueManager.mapVoteCounts()[map.name] ?: 0
            // Only mark the leader green if it actually has votes; otherwise every map stays grey.
            val isLeader = votes > 0 && map.name == leader
            inv.setItem(10 + i, mapItem(map, votes, isLeader))
        }
        player.openInventory(inv)
        return true
    }

    private fun openVoteMenu(player: Player) {
        val inv = Bukkit.createInventory(null, 45, Component.text(VOTE_TITLE))
        border(inv, 45)
        fillMenu(inv, player.locale())
        player.openInventory(inv)
    }

    private fun refreshVoteMenus() {
        Bukkit.getOnlinePlayers().forEach { player ->
            if (player.openInventory.title() != Component.text(VOTE_TITLE)) return@forEach
            fillMenu(player.openInventory.topInventory, player.locale())
        }
    }

    private fun fillMenu(inv: Inventory, locale: Locale) {
        modeOrder.forEachIndexed { i, ns ->
            val name = locale.string("game.$ns.name")
            inv.setItem(10 + i, voteItem(modeMaterials[ns] ?: Material.PAPER, name, QueueManager.modeVoteCounts()[ns] ?: 0, locale.string("vote.category.mode")))
        }
        typeOrder.forEachIndexed { i, ns ->
            val name = locale.string("modifier.$ns.name")
            inv.setItem(19 + i, voteItem(typeMaterials[ns] ?: Material.PAPER, name, QueueManager.typeVoteCounts()[ns] ?: 0, locale.string("vote.category.type")))
        }
        timeOrder.forEachIndexed { i, t ->
            val name = "$t ${locale.string("vote.time.seconds")}"
            inv.setItem(28 + i, voteItem(timeMaterials[t] ?: Material.PAPER, name, QueueManager.timeVoteCounts()[t] ?: 0, locale.string("vote.category.time")))
        }
    }

    private fun border(inv: Inventory, size: Int) {
        val pane = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            val meta = itemMeta
            meta.displayName(Component.text(" "))
            itemMeta = meta
        }
        for (slot in 0 until size) {
            val col = slot % 9
            val row = slot / 9
            val isEdge = col == 0 || col == 8 || row == 0 || row == size / 9 - 1
            if (isEdge) inv.setItem(slot, pane.clone())
        }
    }

    private fun voteItem(material: Material, name: String, votes: Int, category: String): ItemStack {
        val item = ItemStack(material)
        val meta: ItemMeta = item.itemMeta
        meta.displayName(Component.text(name).color(NamedTextColor.GOLD))
        meta.lore(listOf(
            Component.text(category).color(NamedTextColor.DARK_GRAY),
            Component.text("Votes: $votes").color(if (votes > 0) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY),
        ))
        item.itemMeta = meta
        return item
    }

    private fun mapItem(map: ArenaMap, votes: Int, isLeader: Boolean): ItemStack {
        val item = ItemStack(if (isLeader) Material.SLIME_BALL else Material.FIRE_CHARGE)
        val meta: ItemMeta = item.itemMeta
        meta.displayName(Component.text(map.displayName ?: map.name).color(NamedTextColor.GOLD))
        meta.persistentDataContainer.set(MAP_KEY, PersistentDataType.STRING, map.name)
        meta.lore(buildList {
            map.description?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { line ->
                add(Component.text(line).color(NamedTextColor.GRAY))
            }
            add(Component.text("Votes: $votes").color(if (votes > 0) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY))
        })
        item.itemMeta = meta
        return item
    }
}
