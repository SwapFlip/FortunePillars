package com.marcpg.pillarperil.player

import com.marcpg.libpg.display.PlayerMinecraftReceiver
import com.marcpg.libpg.display.SimpleActionBar
import com.marcpg.libpg.display.SimpleScoreboard
import com.marcpg.libpg.display.start
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.util.QueueManager
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.QueueMethod
import com.marcpg.pillarperil.util.playSoundSafe
import com.marcpg.pillarperil.util.toItemStackSafe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class PillarPlayer(player: Player, val game: Game) : PlayerMinecraftReceiver(player) {
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

    val initialSnapshot = PlayerSnapshot(player)

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

            val offhand = player.inventory.itemInOffHand
            if (offhand.type == Material.AIR && kotlin.random.Random.nextInt(5) == 0) {
                player.inventory.setItemInOffHand(item)
            } else if (Configuration.avoidHeldSlot) {
                val contents = player.inventory.contents
                val heldSlot = player.inventory.heldItemSlot
                val freeSlots = contents.indices.filter { it != heldSlot && (contents[it]?.type ?: Material.AIR) == Material.AIR }
                if (freeSlots.isEmpty()) {
                    player.world.dropItemNaturally(player.location, item)
                } else {
                    player.inventory.setItem(freeSlots.random(), item)
                }
            } else {
                player.inventory.addItem(item).values.forEach { leftover ->
                    player.world.dropItemNaturally(player.location, leftover)
                }
            }
        }
        player.playSoundSafe(Sound.ENTITY_ITEM_PICKUP, 0.75f) { Configuration.soundEffectsItem }
    }

    fun clear(display: Boolean = false) {
        if (display) {
            simpleScoreboard?.stop()
            simpleActionBar?.stop()

            player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        }

        player.closeInventory()
        player.inventory.clear()
        player.clearActivePotionEffects()
        initialSnapshot.set(player, restoreGameMode = true, restoreLocation = true)

        if (Configuration.queueMethod == QueueMethod.AUTO)
            bukkitRunLater(60L) { QueueManager.add(player) } // Wait 3 seconds before rejoining queue.
    }

    fun eliminate() = game.eliminate(this)
}
