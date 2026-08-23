package com.swapflip.fortunepillars.player

import com.marcpg.libpg.display.PlayerMinecraftReceiver
import com.marcpg.libpg.display.SimpleScoreboard
import com.marcpg.libpg.display.start
import com.marcpg.libpg.util.bukkitRunLater
import com.swapflip.fortunepillars.game.Game
import com.swapflip.fortunepillars.game.util.QueueManager
import com.swapflip.fortunepillars.util.Configuration
import com.swapflip.fortunepillars.util.QueueMethod
import com.swapflip.fortunepillars.util.playSoundSafe
import com.swapflip.fortunepillars.util.spawnSparkParticle
import com.swapflip.fortunepillars.util.toItemStackSafe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

class PillarPlayer(player: Player, val game: Game, initialSnapshot: PlayerSnapshot? = null) : PlayerMinecraftReceiver(player) {
    companion object {
        private const val RAMPAGE_WINDOW_TICKS = 10 * 20
        private const val RAMPAGE_KILLS = 3

        private val rareItems = setOf(
            Material.NETHERITE_SWORD, Material.NETHERITE_AXE, Material.NETHERITE_PICKAXE,
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
            Material.DIAMOND_SWORD, Material.DIAMOND_AXE, Material.DIAMOND_PICKAXE,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE, Material.TOTEM_OF_UNDYING, Material.ELYTRA, Material.TRIDENT,
        )
    }

    var simpleScoreboard: SimpleScoreboard? = null

    var kills: Int = 0
    var deathTime: Int? = null

    // Recent kill timestamps (server ticks) for the rampage cue: 3 kills within RAMPAGE_WINDOW_TICKS
    // triggers the announcement. Pruned on every kill so only a tight streak counts.
    var recentKillTicks = mutableListOf<Int>()
    fun registerKill(): Boolean {
        val now = Bukkit.getCurrentTick()
        recentKillTicks = recentKillTicks.filter { now - it <= RAMPAGE_WINDOW_TICKS }.toMutableList()
        recentKillTicks.add(now)
        return recentKillTicks.size >= RAMPAGE_KILLS
    }

    // Last player who damaged us and the tick it happened, used to credit void/fall knock-offs.
    var lastDamagedBy: UUID? = null
    var lastDamageTick: Int = Int.MIN_VALUE

    // Display widgets only exist inside the game's world (the PillarPeril world on standard
    // setups); leaving the world hides them via PlayerChangedWorldEvent, returning restarts them.
    fun startWidgets() {
        // The in-game scoreboard can be disabled entirely via `scoreboard.enabled`.
        if (!Configuration.scoreboardEnabled) return
        if (game.info.showScoreboard() && player.world == game.world && simpleScoreboard == null) {
            try {
                simpleScoreboard = game.scoreboard?.invoke(this)
                simpleScoreboard!!.start()
            } catch (e: Exception) {
                game.error("Could not create and initialize scoreboard for $this.", e)
            }
        }
    }

    fun stopWidgets() {
        simpleScoreboard?.stop()
        simpleScoreboard = null
    }

    // Super Star shield: absorbs the next 2 damage events while active (30 seconds).
    var starShieldHits = 0
    var starShieldUntil = 0
    val starShieldActive: Boolean get() = starShieldHits > 0 && Bukkit.getCurrentTick() < starShieldUntil

    // The last player standing during the victory celebration: invincible, unable to modify the
    // arena, and rescued from the void instead of being killed (see the event handlers).
    var winnerProtected = false

    // Snapshot taken before the player entered the queue (captured at queue join) so they get restored to
    // where they actually were before playing. Falls back to a fresh snapshot for non-queue games.
    val initialSnapshot = initialSnapshot ?: PlayerSnapshot(player)

    init {
        startWidgets()
    }

    fun giveItems(available: Collection<Material>, differentItems: Int = 1) {
        // Drawn without replacement: every pick removes the material and all of its weighted entries
        // from the pool, so one drop can never contain the same material twice (the weighted pool
        // repeats materials, so entry-level removal alone would still allow duplicate drops).
        val drawn = buildList {
            val pool = available.toMutableList()
            repeat(differentItems.coerceAtMost(pool.size)) {
                val material = pool.removeAt(pool.indices.random())
                add(material)
                pool.removeAll { it == material }
            }
        }
        drawn.forEach {
            // Power-ups and specials replace the drop entirely: either a plain useful survival item or a
            // special item (Super Star, Fireball, Aid Platform) - the only loot that gets a custom
            // name and lore. Both are capped so they stay rare no matter what the config says.
            // While the lava rises, snowballs and fireballs are pulled from the rolls: knocking
            // players into the flood with them is too brutal.
            val lavaRises = game.modifierOf(com.swapflip.fortunepillars.game.modifier.RisingLavaModifier::class.java) != null
            val namespace = game.info.namespace
            var item = when {
                // Specials are independent of power-ups: they have their own `specials.chance`
                // and should drop even when power-ups are disabled in the config.
                (0..99).random() < game.info.specialChance() ->
                    SpecialItems.randomSpecial(player.locale(), namespace, if (lavaRises) setOf("fireball") else emptySet())
                game.info.powerUpsEnabled() && (0..99).random() < game.info.powerUpChance() ->
                    SpecialItems.randomPowerUp(namespace, if (lavaRises) setOf(Material.SNOWBALL) else emptySet())
                else -> {
                    // Use the already-drawn material: re-drawing from `available` here would defeat the
                    // no-replacement draw above and allow duplicate materials in one drop.
                    var stack = it.toItemStackSafe()
                    // Weapons, tools and armor sometimes drop pre-enchanted. The per-mode/family
                    // chance is rolled inside maybeEnchant, so every configured chance is applied
                    // exactly once.
                    stack = SpecialItems.maybeEnchant(stack, namespace)
                    // Turn raw materials into usable drops: loaded crossbows, potions with a random
                    // effect, suspicious stew with a random effect, small snowball bundles.
                    SpecialItems.refine(stack, namespace)
                }
            }
            for (modifier in game.modifiers) {
                item = modifier.onItemReceive(item)
            }

            // Special items get a launch-y fanfare so they never blend in with the regular loot;
            // the rarest gear gets a high level-up ding.
            when {
                SpecialItems.of(item) != null -> player.playSoundSafe(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.5f, 1.0f)
                item.type in rareItems -> player.playSoundSafe(Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f)
            }

            // Items always land in the next available main inventory slot — never the offhand or armor.
            // `storageContents` is used instead of `contents`, since the latter can also include the
            // armor and offhand slots on player inventories. Ghost amount-0 stacks count as free.
            val contents = player.inventory.storageContents
            val heldSlot = player.inventory.heldItemSlot

            // First merge into a partially filled stack of the same item, so small stacks grow
            // instead of being scattered across empty slots.
            if (item.maxStackSize > 1) {
                val partial = contents.firstOrNull { it != null && it.type == item.type && it.amount > 0 && it.amount < it.maxStackSize }
                if (partial != null) {
                    val add = minOf(item.amount, partial.maxStackSize - partial.amount)
                    partial.amount += add
                    item.amount -= add
                }
            }

            if (item.amount > 0) {
                val nextSlot = contents.indices.firstOrNull { i ->
                    (i != heldSlot || !Configuration.avoidHeldSlot) && (contents[i] == null || contents[i]!!.amount <= 0 || contents[i]!!.type == Material.AIR)
                }
                if (nextSlot != null) {
                    player.inventory.setItem(nextSlot, item)
                } else {
                    player.world.dropItemNaturally(player.location, item)
                }
            }

            // Action mode flourish: every drop bursts into sparks, so the mode feels loud and
            // flashy instead of just being a faster loot cycle.
            if (game.info.namespace == "action")
                player.world.spawnSparkParticle(player.location.clone().add(0.0, 1.2, 0.0), 10, 0.25, 0.08)
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
