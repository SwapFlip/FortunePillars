package com.swapflip.fortunepillars.event

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.util.GameManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

object SpectatorEvents : Listener {
    private fun menuTitle(player: Player): Component = Component.text(player.locale().string("spectator.menu.title"))

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        // The compass works from either hand, so a spectator whose main hand is empty still
        // has a reliable way back to the menu.
        if (event.hand != EquipmentSlot.HAND && event.hand != EquipmentSlot.OFF_HAND) return
        if (event.item?.type != Material.COMPASS) return
        if (event.player.gameMode != GameMode.SPECTATOR) return

        val spectator = GameManager.player(event.player, onlyAlive = false) ?: return
        event.isCancelled = true
        openTeleportMenu(event.player, spectator.game)
    }

    @EventHandler
    fun onInteractEntity(event: PlayerInteractAtEntityEvent) {
        val player = event.player
        if (player.gameMode != GameMode.SPECTATOR) return
        val spectator = GameManager.player(player, onlyAlive = false) ?: return

        val target = event.rightClicked as? Player ?: return
        if (target.gameMode == GameMode.SPECTATOR) return
        // Only same-game targets: a spectator of one game must not teleport into another match.
        if (GameManager.player(target, onlyAlive = true)?.game !== spectator.game) return
        player.teleport(target)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.title() != menuTitle(player)) return
        event.isCancelled = true

        val spectator = GameManager.player(player, onlyAlive = false) ?: return
        val target = (event.currentItem?.itemMeta as? SkullMeta)?.owningPlayer?.uniqueId?.let { Bukkit.getPlayer(it) } ?: return
        // Only alive players of the same game - a spectator can't follow another spectator, and
        // never a player fighting in a different match.
        if (GameManager.player(target, onlyAlive = true)?.game !== spectator.game) return
        player.teleport(target)
        player.closeInventory()
    }

    private fun openTeleportMenu(player: Player, game: Game) {
        val inv = Bukkit.createInventory(null, 27, menuTitle(player))
        game.players.forEachIndexed { i, p ->
            if (i >= 27) return@forEachIndexed
            // Only players still alive appear - dead players (already spectators) aren't worth
            // a slot, and the menu has no way to teleport to them meaningfully.
            if (p.player.gameMode == GameMode.SPECTATOR) return@forEachIndexed

            val head = ItemStack(Material.PLAYER_HEAD)
            val meta = head.itemMeta as SkullMeta
            meta.owningPlayer = p.player
            meta.displayName(component(p.player.name, NamedTextColor.GOLD))
            head.itemMeta = meta
            inv.setItem(i, head)
        }
        player.openInventory(inv)
    }
}