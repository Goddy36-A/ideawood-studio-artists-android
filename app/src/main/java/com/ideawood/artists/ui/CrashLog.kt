package com.ideawood.artists.ui

import android.content.Context
import java.io.File

/** Saves the last crash/error to a file so the next launch can show what went wrong (no computer needed). */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun save(c: Context, where: String, e: Throwable) {
        try {
            val root = generateSequence(e) { it.cause }.last()
            file(c).writeText("$where: ${e.javaClass.name}: ${e.message}\ncause: ${root.javaClass.name}: ${root.message}\n" +
                e.stackTrace.take(6).joinToString("\n"))
        } catch (_: Throwable) {}
    }

    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> save(c, "uncaught(${t.name})", e); prev?.uncaughtException(t, e) }
    }

    /** Returns the saved report once, then clears it. */
    fun read(c: Context): String? = try {
        file(c).takeIf { it.exists() }?.readText()?.take(300)?.also { file(c).delete() }
    } catch (_: Throwable) { null }
}
