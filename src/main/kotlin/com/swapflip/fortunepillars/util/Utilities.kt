package com.swapflip.fortunepillars.util

import com.marcpg.libpg.display.MinecraftReceiver
import com.marcpg.libpg.display.receiver
import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.miniMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.Locale

// One shared deserializer for the whole plugin (MiniMessage's own default is already a singleton,
// but routing every call through one reference keeps the intent explicit and the escaping rules
// consistent).
val MINI_MESSAGE: MiniMessage = MiniMessage.miniMessage()

fun Throwable.trackToFastStats() = Metrics.logError(this)

// Renders a localized key through MiniMessage, so chat messages can carry rich formatting
// (colors, gradients, bold...) while staying fully translatable.
fun Locale.chatComponent(key: String, vararg args: String, color: NamedTextColor? = null): Component {
    val parsed = MINI_MESSAGE.deserialize(string(key, *args))
    return if (color != null) parsed.color(color) else parsed
}

// Player names are user-controlled text: when one gets substituted into a localized string and
// then deserialized through MiniMessage, a '<' would either be parsed as a tag (garbled output)
// or, in strict mode, throw and abort the whole message - so names are always escaped before
// interpolation. This mirrors MiniMessage's own escapeTags behavior.
fun String.escapeTags(): String = replace("<", "\\<")

// Registry lookups are cheap but not free: playSoundSafe runs for every sound the plugin emits,
// so the Sound -> NamespacedKey mapping is resolved once per sound instead of per call.
private val soundKeys = mutableMapOf<Sound, org.bukkit.NamespacedKey>()

private fun soundKey(sound: Sound): org.bukkit.NamespacedKey =
    soundKeys.getOrPut(sound) { Registry.SOUNDS.getKeyOrThrow(sound) }

fun MinecraftReceiver.playSoundSafe(sound: Sound, volume: Float = 1.0f, pitch: Float = 1.0f, requirement: (() -> Boolean) = { true }) {
    if (Configuration.soundEffectsEnabled && requirement())
        this.playSound(soundKey(sound), volume, pitch)
}

fun List<MinecraftReceiver>.playSoundSafe(sound: Sound, volume: Float = 1.0f, pitch: Float = 1.0f, requirement: (() -> Boolean) = { true }) {
    if (Configuration.soundEffectsEnabled && requirement())
        this.receiver().playSound(soundKey(sound), volume, pitch)
}

fun Player.playSoundSafe(sound: Sound, volume: Float = 1.0f, pitch: Float = 1.0f, requirement: (() -> Boolean) = { true }) {
    if (Configuration.soundEffectsEnabled && requirement())
        this.playSound(this, sound, volume, pitch)
}

// Consumes one item from the held stack, replacing it with AIR instead of leaving an amount-0
// "ghost" stack behind, and refreshes the client-side inventory so the change is visible.
fun Player.consumeHeldItem() {
    val slot = inventory.heldItemSlot
    val item = inventory.getItem(slot) ?: return
    if (item.amount <= 1) {
        inventory.setItem(slot, null)
    } else {
        item.amount--
    }
    updateInventory()
}
