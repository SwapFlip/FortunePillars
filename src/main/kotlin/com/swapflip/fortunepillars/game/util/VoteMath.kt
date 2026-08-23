package com.swapflip.fortunepillars.game.util

// Picks the most-voted option; ties are broken randomly. Returns `default` when the list is empty.
fun <T> List<T>.mostVoted(default: T): T {
    val counts = groupingBy { it }.eachCount()
    val max = counts.values.maxOrNull() ?: return default
    return counts.filterValues { it == max }.keys.random()
}

// Resolves a vote category: if the winner equals `sentinel`, pick randomly from `options`
// instead of falling back to the configured default.
fun <T> resolveVote(votes: List<T>, sentinel: T, options: List<T>, default: T): T {
    val winner = votes.mostVoted(default)
    return if (winner == sentinel) options.random() else winner
}
