package com.ideawood.artists

import org.junit.Assert.assertTrue
import org.junit.Test

class VisualsTest {
    @Test
    fun visualsLogic() {
        val failures = VisualsCheck.run()
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
