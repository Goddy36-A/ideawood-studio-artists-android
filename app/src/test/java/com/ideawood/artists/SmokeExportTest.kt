package com.ideawood.artists

import org.junit.Test
import java.io.File

/** Generates real render jobs in build/smoke/jobN; the CI workflow then runs each one through a real ffmpeg + libass. */
class SmokeExportTest {
    @Test
    fun writeJobs() {
        val text = javaClass.classLoader!!.getResourceAsStream("golden.json")!!.bufferedReader().readText()
        val root = File("build/smoke")
        root.deleteRecursively()
        listOf("Neon Night" to 0, "Street Poster" to 1, "Soul & Script" to 2, "Cinematic Gold" to 3, "Clean Minimal" to 0)
            .forEachIndexed { i, (preset, fmt) -> Smoke.generate(text, File(root, "job$i"), preset, fmt) }
    }
}
