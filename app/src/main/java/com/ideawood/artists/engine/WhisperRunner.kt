package com.ideawood.artists.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Runs whisper.cpp like the desktop app does: the `whisper-cli` program built from the same source release
 * (packaged as libwhisper_cli.so so Android allows executing it), JSON parsed by the same code.
 *
 * Phones are weaker and fussier than PCs, so:
 *  - "Fast mode" (default) turns off the slow temperature fall-back and extra sampling (big speed-up on music);
 *  - if the engine crashes, it is retried once, single-threaded;
 *  - every failure says HOW the engine stopped (exit code / signal) so it can be diagnosed from a screenshot.
 */
class WhisperRunner(private val ctx: Context, private val paths: AppPaths, private val settings: Settings) {
    /** Phones with dot-product + fp16 (most 64-bit phones since ~2019) run the optimised build; others the baseline build. */
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

    private class Run(val rc: Int, val tail: List<String>, val cancelled: Boolean, val startError: String? = null)

    private fun command(exe: File, model: File, wav: File, base: String, language: String, prompt: String, threads: Int): List<String> {
        val cmd = mutableListOf(exe.path, "-m", model.path, "-f", wav.path, "-l", language.ifEmpty { "auto" },
            "-ojf", "-of", base, "-t", threads.toString(), "-pp")
        if (settings.speechFast) { cmd += "-nf"; cmd += "-bo"; cmd += "1" }
        if (prompt.isNotBlank()) { cmd += "--prompt"; cmd += prompt.trim().take(400) }
        return cmd
    }

    private suspend fun runOnce(cmd: List<String>, onProgress: (Double) -> Unit, cancelled: () -> Boolean): Run = coroutineScope {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(ctx.cacheDir)
        pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        val proc = try { pb.start() } catch (e: Exception) { return@coroutineScope Run(-1, emptyList(), false, e.message ?: e.javaClass.simpleName) }
        val tail = ArrayDeque<String>()
        var wasCancelled = false
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
                else { tail.addLast(line.take(200)); while (tail.size > 14) tail.removeFirst() }
            }
        }
        val rc = proc.waitFor()
        watcher.cancel()
        Run(rc, tail.toList(), wasCancelled)
    }

    /** "exit code 132 = illegal instruction (...)" - how the engine stopped, in plain words. */
    fun describe(rc: Int): String {
        val sig = if (rc > 128) rc - 128 else rc
        val name = when (sig) {
            4 -> "illegal instruction: this phone's CPU cannot run this engine build"
            6 -> "aborted: internal error, often out of memory"
            7 -> "bus error: memory alignment problem inside the engine"
            8 -> "arithmetic error"
            9 -> "killed by Android: probably out of memory"
            11 -> "crashed (segmentation fault)"
            else -> null
        }
        return if (name != null && (rc > 128 || rc in setOf(4, 6, 7, 9, 11))) "exit code $rc = signal $sig, $name" else "exit code $rc"
    }

    suspend fun transcribe(model: File, wav: File, language: String, prompt: String, onProgress: (Double) -> Unit,
                           cancelled: () -> Boolean): Transcript = withContext(Dispatchers.IO) {
        val exe = cli()
        if (!exe.isFile) throw IllegalStateException("The speech engine is missing from this build. Install the latest APK from GitHub Actions.")
        val work = paths.newWorkDir("asr")
        try {
            val base = File(work, "out").path
            val report = StringBuilder()
            for ((i, t) in listOf(threads(), 1).withIndex()) {
                File("$base.json").delete()
                val r = runOnce(command(exe, model, wav, base, language, prompt, t), onProgress, cancelled)
                if (r.cancelled) throw java.util.concurrent.CancellationException("Cancelled")
                if (r.startError == null && r.rc == 0 && File("$base.json").exists()) {
                    onProgress(100.0)
                    return@withContext Asr.parseWhisperJson(JSONObject(File("$base.json").readText()))
                }
                report.append("Attempt ${i + 1} ($t thread${if (t == 1) "" else "s"}): ")
                    .append(r.startError?.let { "could not start: $it" } ?: describe(r.rc)).append('\n')
                    .append(r.tail.takeLast(4).joinToString("\n")).append('\n')
                if (r.startError != null) break              // no point retrying if the file cannot even start
                if (t == 1) break
                onProgress(0.0)
            }
            throw IllegalStateException("Speech recognition failed (${exe.name}, ${model.name}).\n$report")
        } finally {
            work.deleteRecursively()
        }
    }

    // ----------------------------------------------------------------------- self test (Song tab > Test speech engine)
    private fun silentWav(f: File, seconds: Int = 3) {
        val n = 16000 * seconds * 2
        val b = ByteBuffer.allocate(44 + n).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(n)
        f.writeBytes(b.array())
    }

    private fun deviceInfo(): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val feat = try {
            File("/proc/cpuinfo").readLines().firstOrNull { it.lowercase().startsWith("features") }?.take(170) ?: "(none listed)"
        } catch (_: Exception) { "(unreadable)" }
        return "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
            "CPU ABIs: ${Build.SUPPORTED_ABIS.joinToString()} | cores: ${Runtime.getRuntime().availableProcessors()}\n" +
            "RAM: ${mi.totalMem / 1_000_000} MB total, ${mi.availMem / 1_000_000} MB free now\n" +
            "CPU features: $feat"
    }

    suspend fun selfTest(model: File?): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder(deviceInfo()).append("\n\n")
        val exe = cli()
        if (!exe.isFile) return@withContext sb.append("Speech engine file is MISSING from this build (${exe.name}).").toString()
        sb.append("Engine: ${exe.name} (${exe.length() / 1000} KB)\n")
        // 1) does the program even start?
        val h = runOnce(listOf(exe.path, "-h"), {}, { false })
        sb.append("1) Start-up: ").append(
            if (h.startError != null) "FAILED to start: ${h.startError}"
            else if (h.rc == 0) "OK"
            else "FAILED - ${describe(h.rc)}\n${h.tail.take(3).joinToString("\n")}",
        ).append('\n')
        if (h.startError != null || h.rc != 0) return@withContext sb.toString()
        if (model == null) return@withContext sb.append("2) Recognition test skipped: no speech model installed yet.").toString()
        // 2) can it run the model on 3 seconds of silence?
        val work = paths.newWorkDir("asr")
        try {
            val wav = File(work, "t.wav"); silentWav(wav)
            for (t in listOf(threads(), 1)) {
                File(work, "out.json").delete()
                val t0 = System.currentTimeMillis()
                val r = runOnce(command(exe, model, wav, File(work, "out").path, "en", "", t), {}, { false })
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                val ok = r.startError == null && r.rc == 0 && File(work, "out.json").exists()
                sb.append("2) Recognition with ${model.name}, $t thread${if (t == 1) "" else "s"}: ")
                    .append(if (ok) "OK in ${String.format(java.util.Locale.ROOT, "%.1f", secs)} s" else "FAILED - ${r.startError ?: describe(r.rc)}\n${r.tail.takeLast(5).joinToString("\n")}")
                    .append('\n')
                if (ok) break
            }
        } finally { work.deleteRecursively() }
        sb.toString()
    }
}
