package com.ideawood.artists.engine

import android.content.Context
import com.ideawood.artists.core.Asr
import com.ideawood.artists.core.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Runs whisper.cpp exactly like the desktop app does: the `whisper-cli` program built from the same source release
 * (packaged as libwhisper_cli.so so Android allows executing it) with the same flags, JSON parsed by the same code.
 */
class WhisperRunner(private val ctx: Context, private val paths: AppPaths) {
    /** Phones with dot-product + fp16 (most since ~2019) run the optimised build; older ones the baseline build. */
    private fun fastCpu(): Boolean = try {
        val f = File("/proc/cpuinfo").readText().lowercase()
        f.contains("asimddp") && (f.contains("fphp") || f.contains("asimdhp"))
    } catch (_: Exception) { false }

    fun cli(): File {
        val dir = ctx.applicationInfo.nativeLibraryDir
        val fast = File(dir, "libwhisper_cli_fast.so")
        return if (fast.isFile && fastCpu()) fast else File(dir, "libwhisper_cli.so")
    }

    fun threads(): Int = Runtime.getRuntime().availableProcessors().let { minOf(4, maxOf(2, it / 2)) }

    suspend fun transcribe(model: File, wav: File, language: String, prompt: String, onProgress: (Double) -> Unit,
                           cancelled: () -> Boolean): Transcript = withContext(Dispatchers.IO) {
        val exe = cli()
        if (!exe.isFile) throw IllegalStateException("The speech engine is missing from this build. Install the latest APK from GitHub Actions.")
        val work = paths.newWorkDir("asr")
        try {
            val base = File(work, "out").path
            val cmd = mutableListOf(exe.path, "-m", model.path, "-f", wav.path, "-l", language.ifEmpty { "auto" },
                "-ojf", "-of", base, "-t", threads().toString(), "-pp")
            if (prompt.isNotBlank()) { cmd += "--prompt"; cmd += prompt.trim().take(400) }
            val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(ctx.cacheDir)
            pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
            val proc = pb.start()
            val tail = ArrayDeque<String>()
            var wasCancelled = false
            coroutineScope {
                val watcher = launch {
                    while (isActive) {
                        if (cancelled()) { wasCancelled = true; proc.destroyForcibly(); break }
                        delay(300)
                    }
                }
                val re = Regex("progress\\s*=\\s*(\\d+)\\s*%")
                proc.inputStream.bufferedReader().useLines { seq ->
                    for (line in seq) {
                        val m = re.find(line)
                        if (m != null) onProgress(m.groupValues[1].toDouble())
                        else { tail.addLast(line); while (tail.size > 12) tail.removeFirst() }
                    }
                }
                val rc = proc.waitFor()
                watcher.cancel()
                if (wasCancelled) throw java.util.concurrent.CancellationException("Cancelled")
                val json = File("$base.json")
                if (rc != 0 || !json.exists()) throw IllegalStateException("Speech recognition failed.\n" + tail.takeLast(5).joinToString("\n"))
            }
            onProgress(100.0)
            Asr.parseWhisperJson(JSONObject(File("$base.json").readText()))
        } finally {
            work.deleteRecursively()
        }
    }
}
