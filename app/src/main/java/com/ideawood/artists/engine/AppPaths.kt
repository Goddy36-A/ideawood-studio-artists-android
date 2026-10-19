package com.ideawood.artists.engine

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.File

/** All app folders. Paths used inside ffmpeg filter graphs must stay "safe" (letters, digits, / . _ -). */
class AppPaths(private val ctx: Context) {
    val models: File get() = File(ctx.filesDir, "models").also { it.mkdirs() }
    val fonts: File get() = File(ctx.filesDir, "fonts").also { it.mkdirs() }
    val projects: File get() = File(ctx.filesDir, "projects").also { it.mkdirs() }
    val media: File get() = File(ctx.filesDir, "media").also { it.mkdirs() }
    val out: File get() = File(ctx.cacheDir, "out").also { it.mkdirs() }

    fun newWorkDir(prefix: String): File = File(ctx.cacheDir, "${prefix}_${System.currentTimeMillis()}").also { it.mkdirs() }

    /** Copy the bundled open-licence fonts to disk (libass reads them from a folder) and register them with FFmpegKit. */
    fun installFonts() {
        val names = ctx.assets.list("fonts") ?: emptyArray()
        for (n in names) {
            if (!(n.endsWith(".ttf") || n.endsWith(".otf"))) continue
            val dst = File(fonts, n)
            if (!dst.exists() || dst.length() == 0L) {
                ctx.assets.open("fonts/$n").use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        try {
            FFmpegKitConfig.setFontDirectoryList(ctx, listOf(fonts.absolutePath), emptyMap())
        } catch (_: Throwable) { /* ffmpeg also gets fontsdir= explicitly, so this is only a belt-and-braces step */ }
    }

    fun cleanWork() {
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("render_") || it.name.startsWith("asr_") || it.name.startsWith("frame_") }
            ?.forEach { it.deleteRecursively() }
    }
}
