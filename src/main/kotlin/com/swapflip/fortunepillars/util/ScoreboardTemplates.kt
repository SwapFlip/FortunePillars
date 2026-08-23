package com.swapflip.fortunepillars.util

import com.marcpg.libpg.display.MinecraftReceiver
import com.marcpg.libpg.display.ScoreboardEntry
import com.marcpg.libpg.display.SimpleScoreboard
import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.Locale

// Shared engine for config-driven scoreboard lines. A line like "<gray>Kills: <white><kills>"
// is split ONCE into pre-parsed static segments and named placeholders, so every scoreboard
// update only substitutes the value text - the static MiniMessage markup is never re-parsed.
// Parsing is restricted to a known placeholder set on purpose: a generic "<...>" pattern would
// swallow real MiniMessage tags like <gray> and turn them into empty placeholders.
object ScoreboardTemplates {
    data class Segment(val static: Component?, val placeholder: String?)

    // Splits a config line into static text (deserialized immediately) and placeholder keys.
    fun parse(line: String, knownPlaceholders: Set<String>): List<Segment> {
        val segments = mutableListOf<Segment>()
        val pattern = Regex("<(${knownPlaceholders.joinToString("|")})>")
        var cursor = 0
        for (match in pattern.findAll(line)) {
            if (match.range.first > cursor)
                segments += Segment(MINI_MESSAGE.deserialize(line.substring(cursor, match.range.first)), null)
            segments += Segment(null, match.groupValues[1])
            cursor = match.range.last + 1
        }
        if (cursor < line.length)
            segments += Segment(MINI_MESSAGE.deserialize(line.substring(cursor)), null)
        return segments
    }

    // Renders a parsed template: static segments pass through, placeholders are resolved to
    // plain strings (which go through MiniMessage so translations can carry formatting).
    fun render(template: List<Segment>, resolve: (String) -> String): Component =
        Component.empty().children(template.map { seg ->
            seg.static ?: MINI_MESSAGE.deserialize(resolve(seg.placeholder ?: ""))
        })

    // The server's local time as "HH:mm" - shared by the in-game and queue scoreboards.
    fun clock(): String =
        java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    // Compass direction a player is facing, from their yaw (0 = south, 90 = west, ...).
    fun facing(yaw: Float): String {
        val normalized = ((yaw % 360f) + 360f) % 360f
        val index = ((normalized + 45f) / 90f).toInt() % 4
        return listOf("S", "W", "N", "E")[index]
    }

    // A sidebar line driven by a pre-parsed template. On every scoreboard update each placeholder
    // is resolved through `resolve` (receiving the viewer's locale and the placeholder name), so
    // values can be localized per player. With showNumbers disabled the line's score number is
    // hidden via a blank number format (1.20.3+ API - safe on this 1.20.4 target).
    class TemplateEntry(
        private val template: List<Segment>,
        private val showNumbers: Boolean,
        private val resolve: (Locale, String) -> String,
    ) : ScoreboardEntry() {
        private var last: Component? = null

        override fun init(index: Int, board: SimpleScoreboard) {
            super.init(index, board)
            if (!showNumbers)
                score.numberFormat(NumberFormat.blank())
        }

        override fun update(board: SimpleScoreboard) {
            val next = render(template) { key -> resolve(board.receiver.locale(), key) }
            if (next == last) return
            last = next
            score.customName(next)
        }
    }

    // A sidebar line whose content is produced by `build(player)` on every update. Like TemplateEntry
    // it skips the customName write when the rendered component is unchanged, so static or
    // slowly-changing lines don't churn the scoreboard packet each tick.
    class CachedEntry(private val produce: (MinecraftReceiver) -> Component) : ScoreboardEntry() {
        private var last: Component? = null
        override fun update(board: SimpleScoreboard) {
            val next = produce(board.receiver)
            if (next == last) return
            last = next
            score.customName(next)
        }
    }
}
