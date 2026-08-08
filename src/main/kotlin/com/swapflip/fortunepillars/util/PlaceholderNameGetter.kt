package com.swapflip.fortunepillars.util

data class PlaceholderNameGetter(val base: String) {
    operator fun invoke(values: Map<String, Any>): String {
        var result = base
        values.forEach { (key, value) -> result = result.replace("{$key}", value.toString()) }
        return result
    }

    operator fun invoke(vararg values: Pair<String, Any>): String = invoke(mapOf(*values))
}
