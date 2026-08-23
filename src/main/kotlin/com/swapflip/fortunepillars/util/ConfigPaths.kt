package com.swapflip.fortunepillars.util

object ConfigPaths {
    const val DEATH_HEIGHT = "death-height"
    const val BORDER_RADIUS = "border.radius"
    const val BORDER_BOTTOM_OFFSET = "border.bottom-offset"
    const val BORDER_TOP_OFFSET = "border.top-offset"
    const val AUTO_RELOAD = "config.auto-reload"
    const val REWARDS_WIN_AMOUNT = "rewards.win-amount"
    const val REWARDS_WIN_COMMANDS = "rewards.win-commands"
    const val LEADERBOARD_SIZE = "leaderboard.size"
    const val ITEMS_POOL = "items.pool"
    const val MODIFIERS_CUSTOM_NAMES = "modifiers-customization.custom-names"
    const val MODIFIERS_CUSTOM_DESCRIPTIONS = "modifiers-customization.custom-descriptions"
    const val MAPS_CUSTOM_MATERIALS = "maps-customization.custom-materials"

    fun modeBlacklist(namespace: String) = "modes.$namespace.blacklist"
    fun modeLegacy(namespace: String, path: String) = "modes.$namespace.$path"
    fun modifier(modifier: String, key: String) = "modifiers.$modifier.$key"
}
