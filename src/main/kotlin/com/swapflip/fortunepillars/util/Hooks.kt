package com.swapflip.fortunepillars.util

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.UUID

// Optional third-party integrations that are not compile-time dependencies: they are probed at
// runtime with reflection so the plugin keeps building (and running) whether or not the server has
// Vault or LuckPerms installed. Every call degrades gracefully to a no-op when the hook is absent.
object Hooks {
    private var economy: Any? = null
    private var depositMethod: java.lang.reflect.Method? = null

    fun init() {
        economy = null
        depositMethod = null
        try {
            val vault = Bukkit.getPluginManager().getPlugin("Vault")
            if (vault != null && vault.isEnabled) {
                val econClass = Class.forName("net.milkbowl.vault.economy.Economy")
                val registration = Bukkit.getServicesManager().getRegistration(econClass)
                if (registration != null) {
                    economy = registration.provider
                    depositMethod = econClass.getMethod("depositPlayer", OfflinePlayer::class.java, Double::class.javaPrimitiveType)
                }
            }
        } catch (_: Throwable) {
            economy = null
        }
    }

    val hasEconomy: Boolean get() = economy != null

    // Deposits `amount` into the player's Vault economy. Returns true on success.
    fun deposit(player: OfflinePlayer, amount: Double): Boolean {
        val econ = economy ?: return false
        val method = depositMethod ?: return false
        return try {
            val result = method.invoke(econ, player, amount)
            result?.javaClass?.getMethod("transactionSuccess")?.invoke(result) as? Boolean ?: true
        } catch (_: Throwable) {
            false
        }
    }

    // The player's primary LuckPerms group name, or "" when LuckPerms isn't present.
    fun rankName(uuid: UUID): String {
        return try {
            val lp = Bukkit.getPluginManager().getPlugin("LuckPerms") ?: return ""
            if (!lp.isEnabled) return ""
            val userManager = lp.javaClass.getMethod("getUserManager").invoke(lp)
            val user = userManager.javaClass.getMethod("getUser", UUID::class.java).invoke(userManager, uuid)
            user?.javaClass?.getMethod("getPrimaryGroup")?.invoke(user) as? String ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
