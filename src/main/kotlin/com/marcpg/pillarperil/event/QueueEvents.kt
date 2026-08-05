package com.marcpg.pillarperil.event

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.marcpg.pillarperil.game.util.Cage
import com.marcpg.pillarperil.game.util.QueueManager
import com.marcpg.pillarperil.util.Configuration
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
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
import java.util.Locale

object QueueEvents : Listener {
    private const val VOTE_TITLE = "Vote for Game Mode"

    private val leaving = mutableSetOf<Player>()

    private val modeOrder = listOf("weak", "normal", "balanced", "op", "shuffle", "swapper")
    private val typeOrder = listOf("normal", "lava-rises", "tnt-falls", "border-shrinks", "speedrunner")
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
        "border-shrinks" to Material.BARRIER,
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
        if (event.view.title() != Component.text(VOTE_TITLE)) return
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        if (event.currentItem == null) return

        when (event.rawSlot) {
            in 0..8 -> modeOrder.getOrNull(event.rawSlot)?.let { QueueManager.recordVote(player, mode = it) }
            in 9..17 -> typeOrder.getOrNull(event.rawSlot - 9)?.let { QueueManager.recordVote(player, type = it) }
            in 18..26 -> timeOrder.getOrNull(event.rawSlot - 18)?.let { QueueManager.recordVote(player, time = it) }
        }

        refreshVoteMenus()
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

    private fun openVoteMenu(player: Player) {
        val inv = Bukkit.createInventory(null, 27, Component.text(VOTE_TITLE))
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
            inv.setItem(i, voteItem(modeMaterials[ns] ?: Material.PAPER, name, QueueManager.modeVoteCounts()[ns] ?: 0, locale.string("vote.category.mode")))
        }
        typeOrder.forEachIndexed { i, ns ->
            val name = locale.string("modifier.$ns.name")
            inv.setItem(9 + i, voteItem(typeMaterials[ns] ?: Material.PAPER, name, QueueManager.typeVoteCounts()[ns] ?: 0, locale.string("vote.category.type")))
        }
        timeOrder.forEachIndexed { i, t ->
            val name = "$t ${locale.string("vote.time.seconds")}"
            inv.setItem(18 + i, voteItem(timeMaterials[t] ?: Material.PAPER, name, QueueManager.timeVoteCounts()[t] ?: 0, locale.string("vote.category.time")))
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
}