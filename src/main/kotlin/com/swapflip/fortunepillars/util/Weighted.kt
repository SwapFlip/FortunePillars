package com.swapflip.fortunepillars.util

class WeightedBag<T>(entries: Map<T, Int>) {
    private val items: List<T>
    private val cumulative: IntArray
    private val total: Int
    init {
        val its = mutableListOf<T>(); val cum = mutableListOf<Int>(); var sum = 0
        entries.forEach { (it, w) -> if (w > 0) { sum += w; its += it; cum += sum } }
        items = its; cumulative = cum.toIntArray(); total = sum
    }
    fun random(rng: kotlin.random.Random = kotlin.random.Random.Default): T? {
        if (total == 0) return null
        val roll = rng.nextInt(total)
        var lo = 0; var hi = cumulative.size
        while (lo < hi) { val m = (lo + hi) / 2; if (cumulative[m] <= roll) lo = m + 1 else hi = m }
        return items.getOrNull(lo)
    }
}
