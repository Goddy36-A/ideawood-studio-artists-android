package com.ideawood.artists.engine

import com.ideawood.artists.core.Applied
import com.ideawood.artists.core.VisualItem
import com.ideawood.artists.core.VisualsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Client for the lyric-visual endpoint. Always call from a background job (it blocks on the network). */
class CloudVisuals(private val settings: Settings) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)        // free hosting can take ~1 minute to wake up
        .build()

    suspend fun fetch(lines: List<String>, media: String): List<VisualItem> = withContext(Dispatchers.IO) {
        val key = settings.visualsKey
        if (key.isBlank()) throw IllegalStateException("Enter your visuals key first.")
        val req = Request.Builder()
            .url(settings.visualsBase.trimEnd('/') + "/v1/visuals")
            .header("X-API-Key", key)
            .header("User-Agent", "IdeawoodStudio-Android")
            .post(VisualsApi.requestBody(lines, media).toRequestBody("application/json".toMediaType()))
            .build()
        var lastError = ""
        for (attempt in 1..2) {
            try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    when {
                        resp.code == 401 || resp.code == 403 ->
                            throw IllegalStateException("The visuals service did not accept the key. Check it and try again.")
                        resp.code == 429 -> throw IllegalStateException("Too many requests - wait a minute and try again.")
                        resp.code in 400..499 -> throw IllegalStateException("The visuals service refused the request (${resp.code}) ${VisualsApi.errorText(body)}".trim())
                        resp.code >= 500 -> lastError = "server error ${resp.code} ${VisualsApi.errorText(body)}".trim()
                        else -> return@withContext VisualsApi.parse(body)
                    }
                }
            } catch (e: IllegalStateException) { throw e
            } catch (e: IOException) { lastError = e.message ?: "network problem"
            } catch (e: org.json.JSONException) { throw IllegalStateException("The visuals service sent an answer I could not read.") }
            if (attempt == 1) delay(4000)             // the server may still be waking up: one more try
        }
        throw IllegalStateException("The visuals service is not answering (the free server may be asleep - try again in a minute). $lastError".trim())
    }

    /** Download one picture/clip into [destNoExt]+extension. Returns what was saved. */
    suspend fun download(item: VisualItem, destNoExt: File, maxBytes: Long = 60_000_000L): Applied = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(item.url).header("User-Agent", "IdeawoodStudio-Android").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("download failed (${resp.code})")
            val type = (resp.body?.contentType()?.toString() ?: "").lowercase()
            val kind = when {
                type.startsWith("video/") -> "video"
                type.startsWith("image/") -> "image"
                else -> item.kind
            }
            val ext = when {
                type.contains("jpeg") || type.contains("jpg") -> "jpg"
                type.contains("png") -> "png"
                type.contains("webp") -> "webp"
                type.contains("mp4") -> "mp4"
                type.contains("webm") -> "webm"
                type.contains("quicktime") -> "mov"
                else -> item.url.substringBefore('?').substringAfterLast('.', if (kind == "video") "mp4" else "jpg").lowercase().take(4)
            }
            val len = resp.body?.contentLength() ?: -1L
            if (len > maxBytes) throw IOException("file too large")
            val dest = File(destNoExt.parentFile, destNoExt.name + "." + ext)
            var total = 0L
            resp.body!!.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) { dest.delete(); throw IOException("file too large") }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total < 1000) { dest.delete(); throw IOException("empty file") }
            Applied(dest.path, kind, item.credit)
        }
    }
}
