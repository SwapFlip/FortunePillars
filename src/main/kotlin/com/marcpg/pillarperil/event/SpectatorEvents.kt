package com.marcpg.pillarperil.event

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.locale
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.util.GameManager
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
        if (event.hand != EquipmentSlot.HAND) return
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
        if (GameManager.player(player, onlyAlive = false) == null) return

        val target = event.rightClicked as? Player ?: return
        if (target.gameMode == GameMode.SPECTATOR) return
        player.teleport(target)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.title() != menuTitle(player)) return
        event.isCancelled = true

        val target = (event.currentItem?.itemMeta as? SkullMeta)?.owningPlayer ?: return
        player.teleport(Bukkit.getPlayer(target.uniqueId) ?: return)
        player.closeInventory()
    }

    private fun openTeleportMenu(player: Player, game: Game) {
        val inv = Bukkit.createInventory(null, 27, menuTitle(player))
        game.players.forEachIndexed { i, p ->
            if (i >= 27) return@forEachIndexed

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