package com.swapflip.fortunepillars.util

import kotlin.test.Test
import kotlin.test.assertEquals

class WorldManagerNameTest {
    @Test
    fun `game world name follows prefix convention`() {
        assertEquals("pillarperil_game_5", WorldManager.gameWorldName(5))
    }
}
