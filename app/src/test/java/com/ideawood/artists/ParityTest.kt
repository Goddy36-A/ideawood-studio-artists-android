package com.ideawood.artists

import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin engine must behave exactly like the desktop (Python) engine. Golden data: tools/gen_golden.py. */
class ParityTest {
    @Test
    fun matchesDesktopEngine() {
        val text = javaClass.classLoader!!.getResourceAsStream("golden.json")!!.bufferedReader().readText()
        val (checks, failures) = Parity.run(text)
        assertTrue("$checks checks, ${failures.size} mismatches:\n" + failures.take(8).joinToString("\n\n"), failures.isEmpty())
    }
}
