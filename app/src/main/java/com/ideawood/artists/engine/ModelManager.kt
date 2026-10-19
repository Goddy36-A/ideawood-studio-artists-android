package com.ideawood.artists.engine

import android.content.Context
import android.net.Uri
import com.ideawood.artists.core.Asr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Whisper model files: downloaded once from Hugging Face (resumable) or imported from a file the user already has. */
class ModelManager(private val ctx: Context, private val paths: AppPaths) {
    private fun ok(f: File) = f.isFile && f.length() > 10_000_000

    fun find(key: String): File? {
        val info = Asr.MODELS[key] ?: return null
        return File(paths.models, info.file).takeIf { ok(it) }
    }

    fun installed(): List<String> = Asr.MODELS.keys.filter { find(it) != null }

    /** Best model available, preferring the one the user chose. */
    fun best(preferred: String): Pair<String, File>? {
        find(preferred)?.let { return preferred to it }
        for (k in Asr.MODELS.keys) find(k)?.let { return k to it }
        return null
    }

    suspend fun download(key: String, onProgress: (Double) -> Unit, cancelled: () -> Boolean): File = withContext(Dispatchers.IO) {
        val info = Asr.MODELS.getValue(key)
        val dest = File(paths.models, info.file)
        val part = File(paths.models, info.file + ".part")
        var have = if (part.exists()) part.length() else 0L
        val conn = URL(Asr.MODEL_URL.format(info.file)).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000; conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "IdeawoodStudio")
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("Download failed (HTTP $code). Check your internet connection and try again.")
            if (code == 200) have = 0                       // server ignored Range: start over
            val total = have + conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = have
                    while (true) {
                        if (cancelled()) throw java.util.concurrent.CancellationException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done * 100.0 / total)
                    }
                }
            }
        } finally { conn.disconnect() }
        if (part.length() < 10_000_000) { part.delete(); throw java.io.IOException("The model download was incomplete. Please try again.") }
        dest.delete()
        if (!part.renameTo(dest)) throw java.io.IOException("Could not save the model file.")
        onProgress(100.0)
        dest
    }

    /** The artist already has a ggml model file (e.g. ggml-small.en.bin): copy it in under the expected name. */
    suspend fun importFile(key: String, uri: Uri): File = withContext(Dispatchers.IO) {
        val info = Asr.MODELS.getValue(key)
        val dest = File(paths.models, info.file)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        if (!ok(dest)) { dest.delete(); throw java.io.IOException("That file does not look like a Whisper model.") }
        dest
    }
}
