package com.ideawood.artists

fun main() {
    val f = VisualsCheck.run()
    f.forEach { println("FAIL: $it") }
    println("visuals checks: ${f.size} failures")
    if (f.isNotEmpty()) System.exit(1)
}
