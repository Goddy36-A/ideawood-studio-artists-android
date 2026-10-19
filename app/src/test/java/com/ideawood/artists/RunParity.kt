package com.ideawood.artists

import java.io.File

/** Local runner: java -cp ... com.ideawood.artists.RunParityKt app/src/test/resources/golden.json */
fun main(args: Array<String>) {
    val (checks, failures) = Parity.run(File(args[0]).readText())
    failures.take(25).forEach { println("FAIL: $it\n") }
    println("$checks checks, ${failures.size} mismatches")
    if (failures.isNotEmpty()) System.exit(1)
}
