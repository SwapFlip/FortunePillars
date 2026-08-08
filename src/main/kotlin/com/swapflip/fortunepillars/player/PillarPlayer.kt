package com.swapflip.fortunepillars.player

import com.marcpg.libpg.display.PlayerMinecraftReceiver
import com.marcpg.libpg.display.SimpleActionBar
import com.marcpg.libpg.display.SimpleScoreboard
import com.marcpg.libpg.display.start
import com.marcpg.libpg.util.bukkitRunLater
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.util.QueueManager
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.QueueMethod
import com.swapflip.fortunepillars.util.playSoundSafe
import com.swapflip.fortunepillars.util.toItemStackSafe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class PillarPlayer(player: Player, val game: Game, initialSnapshot: PlayerSnapshot? = null) : PlayerMinecraftReceiver(player) {
    companion object {
        private val rareItems = setOf(
            Material.NETHERITE_SWORD, Material.NETHERITE_AXE, Material.NETHERITE_PICKAXE,
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
            Material.DIAMOND_SWORD, Material.DIAMOND_AXE, Material.DIAMOND_PICKAXE,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE, Material.TOTEM_OF_UNDYING, Material.ELYTRA, Material.TRIDENT,
        )
    }

    var simpleScoreboard: SimpleScoreboard? = null
    var simpleActionBar: SimpleActionBar? = null

    var kills: Int = 0
    var deathTime: Int? = null

    // Last player who damaged us and the tick it happened, used to credit void/fall knock-offs.
    var lastDamagedBy: UUID? = null
    var lastDamageTick: Int = Int.MIN_VALUE

    // Snapshot taken before the player entered the queue (captured at queue join) so they get restored to
    // where they actually were before playing. Falls back to a fresh snapshot for non-queue games.
    val initialSnapshot = initialSnapshot ?: PlayerSnapshot(player)

    init {
        if (game.info.showScoreboard()) {
            try {
                simpleScoreboard = game.scoreboard?.invoke(this)
                simpleScoreboard!!.start()
            } catch (e: Exception) {
                game.error("Could not create and initialize scoreboard for $this.", e)
            }
        }

        if (game.info.showActionBar()) {
            try {
                simpleActionBar = game.actionBar?.invoke(this)
                simpleActionBar!!.start()
            } catch (e: Exception) {
                game.error("Could not create and initialize action bar for $this.", e)
            }
        }
    }

    fun giveItems(available: Collection<Material>, differentItems: Int = 1) {
        repeat(differentItems) {
            var item = available.random().toItemStackSafe()
            for (modifier in game.modifiers) {
                item = modifier.onItemReceive(item)
            }

            if (item.type in rareItems) {
                playSoundSafe(Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f)
            }

            // Items always land in the next available main inventory slot — never the offhand or armor.
            // `storageContents` is used instead of `contents`, since the latter can also include the
            // armor and offhand slots on player inventories.
            val contents = player.inventory.storageContents
            val heldSlot = player.inventory.heldItemSlot
            val nextSlot = contents.indices.firstOrNull { i ->
                (i != heldSlot || !Configuration.avoidHeldSlot) && (contents[i]?.type ?: Material.AIR) == Material.AIR
            }
            if (nextSlot != null) {
                player.inventory.setItem(nextSlot, item)
            } else {
                player.world.dropItemNaturally(player.location, item)
            }
        }
        player.playSoundSafe(Sound.ENTITY_ITEM_PICKUP, 0.75f) { Configuration.soundEffectsItem }
    }

    // Restores the pre-queue state. Safe to call when offline: teleportation is skipped and the
    // player is relocated to the snapshot location by onPlayerJoin when they come back online.
    fun restore() {
        initialSnapshot.set(player, restoreGameMode = true, restoreLocation = true)
    }

    fun clear(display: Boolean = false) {
        if (display) {
            simpleScoreboard?.stop()
            simpleActionBar?.stop()

            if (player.isOnline)
                player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        }

        if (player.isOnline) {
            player.closeInventory()
            player.inventory.clear()
            player.clearActivePotionEffects()
        }
        restore()

        if (Configuration.queueMethod == QueueMethod.AUTO)
            bukkitRunLater(60L) {
                // Re-check online status when the task fires, not just when it was scheduled: a
                // player who disconnected right after the game won't get re-queued while offline.
                if (player.isOnline)
                    QueueManager.add(player)
            } // Wait 3 seconds before rejoining queue.
    }

    // Clears the inventory but keeps whatever the player placed into the offhand, so a shuffle never
    // wipes an item they chose to carry.
    fun clearKeepOffhand() {
        val offhand = player.inventory.itemInOffHand
        player.inventory.clear()
        if (offhand.type != Material.AIR)
            player.inventory.setItemInOffHand(offhand)
    }

    fun eliminate() = game.eliminate(this)
}
