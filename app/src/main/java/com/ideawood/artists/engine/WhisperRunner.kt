package com.ideawood.artists.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.ideawood.artists.core.Asr
import com.ideawood.artists.core.AudioChunker
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
import java.security.MessageDigest
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

    companion object {
        /** Each piece of the song goes to a fresh whisper process; the overlap gives words at the cut edges full context. */
        const val CHUNK_SECONDS = 90.0
        const val OVERLAP_SECONDS = 3.0
    }

    /** `whisper-cli -h` output, read once: tells us which options this engine build really has. */
    private val helpText: String by lazy {
        try {
            val pb = ProcessBuilder(listOf(cli().path, "-h")).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
            val proc = pb.start()
            val t = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            t
        } catch (_: Exception) { "" }
    }

    private fun supports(flag: String) = helpText.contains(flag)
    private fun useFlashAttn() = settings.speechLowMem && supports("--flash-attn")

    fun cli(): File {
        val dir = ctx.applicationInfo.nativeLibraryDir
        val fast = File(dir, "libwhisper_cli_fast.so")
        return if (fast.isFile && fastCpu()) fast else File(dir, "libwhisper_cli.so")
    }

    fun threads(): Int = Runtime.getRuntime().availableProcessors().let { minOf(4, maxOf(2, it / 2)) }

    private class Run(val rc: Int, val tail: List<String>, val cancelled: Boolean, val startError: String? = null)

    private fun command(exe: File, model: File, wav: File, base: String, language: String, prompt: String, threads: Int,
                        flash: Boolean = useFlashAttn(), greedy: Boolean = false): List<String> {
        val cmd = mutableListOf(exe.path, "-m", model.path, "-f", wav.path, "-l", language.ifEmpty { "auto" },
            "-ojf", "-of", base, "-t", threads.toString(), "-pp")
        if (settings.speechFast) { cmd += "-nf"; cmd += "-bo"; cmd += "1" }
        if (flash) cmd += "-fa"                                   // same results, smaller compute buffers
        if (greedy && supports("--beam-size")) { cmd += "-bs"; cmd += "1" }   // last-resort retry only: beam search uses several times the decoder memory
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

    /** Identifies this song+model+settings so a half-finished transcription can be resumed after the app was killed. */
    fun ckptKey(audio: File, model: File, language: String, prompt: String): String {
        val raw = "${audio.length()}|${audio.lastModified()}|${model.name}|${model.length()}|$language|" +
            "${settings.speechFast}|${settings.speechLowMem}|$CHUNK_SECONDS|$prompt"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun pruneCheckpoints(keep: File?) {
        val root = File(ctx.filesDir, "asr_ckpt")
        val stale = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.forEach { if (it != keep && it.lastModified() < stale) it.deleteRecursively() }
    }

    /**
     * One piece of the song. Tries, in order: normal -> 1 thread without flash attention and with greedy decoding
     * -> the same again after a pause (lets Android free memory). Returns the whisper JSON text.
     */
    private suspend fun transcribeChunk(exe: File, model: File, wav: File, base: String, language: String, prompt: String,
                                        label: String, onProgress: (Double) -> Unit, cancelled: () -> Boolean): String {
        val report = StringBuilder()
        // (threads, flash attention, greedy)
        val plans = listOf(Triple(threads(), true, false), Triple(1, false, true), Triple(1, true, true))
        for ((i, pl) in plans.withIndex()) {
            if (cancelled()) throw java.util.concurrent.CancellationException("Cancelled")
            File("$base.json").delete()
            if (i == 2) delay(6000)
            onProgress(0.0)
            val flash = pl.second && useFlashAttn()
            val r = runOnce(command(exe, model, wav, base, language, prompt, pl.first, flash, pl.third), onProgress, cancelled)
            if (r.cancelled) throw java.util.concurrent.CancellationException("Cancelled")
            val out = File("$base.json")
            if (r.startError == null && r.rc == 0 && out.exists()) return out.readText()
            report.append("Attempt ${i + 1} ($label, ${pl.first} thread${if (pl.first == 1) "" else "s"}${if (pl.third) ", greedy" else ""}): ")
                .append(r.startError?.let { "could not start: $it" } ?: describe(r.rc)).append('\n')
                .append(r.tail.takeLast(4).joinToString("\n")).append('\n')
            if (r.startError != null) break
        }
        throw IllegalStateException(report.toString())
    }

    /**
     * The song is cut into ~90 s pieces (3 s overlap), each transcribed by a fresh whisper process. Memory is returned
     * to Android between pieces, a kill only loses one piece, and finished pieces are saved so running Auto-sync again
     * continues where it stopped. [ckptKey] comes from [ckptKey]; null disables saving.
     */
    suspend fun transcribe(model: File, wav: File, language: String, prompt: String, onProgress: (Double) -> Unit,
                           cancelled: () -> Boolean, ckptKey: String? = null): Transcript = withContext(Dispatchers.IO) {
        val exe = cli()
        if (!exe.isFile) throw IllegalStateException("The speech engine is missing from this build. Install the latest APK from GitHub Actions.")
        val info = AudioChunker.readWavInfo(wav)
        val pieces = AudioChunker.plan(info.seconds, CHUNK_SECONDS, OVERLAP_SECONDS)
        val ckpt = ckptKey?.let { File(ctx.filesDir, "asr_ckpt/$it").also { d -> d.mkdirs() } }
        pruneCheckpoints(ckpt)
        val work = paths.newWorkDir("asr")
        try {
            var lang = language
            val parts = mutableListOf<Pair<AudioChunker.Piece, com.ideawood.artists.core.Transcript>>()
            val n = pieces.size
            for (p in pieces) {
                if (cancelled()) throw java.util.concurrent.CancellationException("Cancelled")
                val saved = ckpt?.let { File(it, "c${p.index}.json") }
                var json: JSONObject? = null
                if (saved != null && saved.isFile) {
                    json = try { JSONObject(saved.readText()) } catch (_: Exception) { saved.delete(); null }
                }
                if (json == null) {
                    val chunkWav = File(work, "chunk.wav")
                    AudioChunker.writeChunk(wav, info, p, chunkWav)
                    val label = "part ${p.index + 1} of $n"
                    val text = try {
                        transcribeChunk(exe, model, chunkWav, File(work, "out").path, lang, prompt, label,
                            { f -> onProgress((p.index * 100.0 + f) / n) }, cancelled)
                    } catch (e: java.util.concurrent.CancellationException) {
                        throw e                               // a user cancel is not a failure (it is an IllegalStateException subclass)
                    } catch (e: IllegalStateException) {
                        throw IllegalStateException("Speech recognition failed at $label (${exe.name}, ${model.name}).\n${e.message}\n" +
                            "Finished parts are saved: run Auto-sync again to continue from this part.")
                    }
                    json = JSONObject(text)
                    saved?.writeText(text)
                    chunkWav.delete()
                    System.gc()
                    delay(800)
                }
                if (lang.isEmpty() || lang == "auto") {      // keep every piece in the language found in the first one
                    val d = json.optJSONObject("result")?.optString("language", "") ?: ""
                    if (d.isNotBlank()) lang = d
                }
                parts.add(p to Asr.parseWhisperJson(json))
                onProgress((p.index + 1) * 100.0 / n)
            }
            val merged = AudioChunker.merge(parts)
            ckpt?.deleteRecursively()
            merged
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
        sb.append("Options: flash-attention ${if (supports("--flash-attn")) (if (settings.speechLowMem) "ON" else "available, off") else "not in this engine build"}, ")
            .append("greedy retry ${if (supports("--beam-size")) "available" else "not available"}\n")
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
