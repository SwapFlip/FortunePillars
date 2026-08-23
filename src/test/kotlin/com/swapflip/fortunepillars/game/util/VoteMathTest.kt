package com.swapflip.fortunepillars.game.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoteMathTest {
    @Test
    fun `mostVoted picks the highest count`() {
        assertEquals("a", listOf("a", "a", "b").mostVoted("default"))
    }

    @Test
    fun `mostVoted returns default on empty`() {
        assertEquals("default", emptyList<String>().mostVoted("default"))
    }

    @Test
    fun `resolveVote returns random option when sentinel wins`() {
        val options = listOf("x", "y", "z")
        repeat(20) {
            val result = resolveVote(listOf("__random__"), "__random__", options, "default")
            assertTrue(result in options)
        }
    }

    @Test
    fun `resolveVote returns actual winner otherwise`() {
        assertEquals("b", resolveVote(listOf("b", "b", "a"), "__random__", listOf("a", "b"), "default"))
    }
}
