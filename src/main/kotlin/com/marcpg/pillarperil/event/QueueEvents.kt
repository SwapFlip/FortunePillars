package com.marcpg.pillarperil.event

import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.pillarperil.Registry
import com.marcpg.pillarperil.game.util.QueueManager
import com.marcpg.pillarperil.util.Configuration
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

object QueueEvents : Listener {
    private const val VOTE_TITLE = "Vote for Game Mode"

    private val leaving = mutableSetOf<Player>()

    private val modeMaterials = mapOf(
        "blocky" to Material.BRICKS,
        "chaos" to Material.TNT,
        "classic" to Material.DIAMOND_SWORD,
        "item-only" to Material.STICK,
        "item-shuffle" to Material.SHULKER_BOX,
        "original" to Material.BEDROCK,
        "player-shuffle" to Material.PLAYER_HEAD,
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
        val item = event.currentItem ?: return
        val mode = item.itemMeta?.displayName()?.let(PlainTextComponentSerializer.plainText()::serialize)?.lowercase() ?: return

        QueueManager.recordVote(player, mode)
        player.sendMessage(component("Voted for mode: ", NamedTextColor.GREEN).append(component(mode, NamedTextColor.GOLD)))
        refreshVoteMenus()
        player.closeInventory()
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (event.player in QueueManager.queue) event.isCancelled = true
    }

    private fun openVoteMenu(player: Player) {
        val inv = Bukkit.createInventory(null, 9, Component.text(VOTE_TITLE))
        Registry.modes.entries.forEachIndexed { i, entry ->
            if (i >= 9) return@forEachIndexed
            inv.setItem(i, voteItem(entry.key, entry.value.gameInfo.itemCountdown()))
        }
        player.openInventory(inv)
    }

    private fun refreshVoteMenus() {
        Bukkit.getOnlinePlayers().forEach { player ->
            if (player.openInventory.title() != Component.text(VOTE_TITLE)) return@forEach
            val inv = player.openInventory.topInventory
            Registry.modes.entries.forEachIndexed { i, entry ->
                if (i >= 9) return@forEachIndexed
                inv.setItem(i, voteItem(entry.key, entry.value.gameInfo.itemCountdown()))
            }
        }
    }

    private fun voteItem(namespace: String, cooldown: Long): ItemStack {
        val item = ItemStack(modeMaterials[namespace] ?: Material.PAPER)
        val meta: ItemMeta = item.itemMeta
        meta.displayName(Component.text(namespace.replaceFirstChar(Char::uppercase)).color(NamedTextColor.GOLD))
        val votes = QueueManager.voteCounts()[namespace] ?: 0
        meta.lore(listOf(
            Component.text("Cooldown: ${cooldown}s").color(NamedTextColor.GRAY),
            Component.text("Votes: $votes").color(if (votes > 0) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY),
        ))
        item.itemMeta = meta
        return item
    }
}
