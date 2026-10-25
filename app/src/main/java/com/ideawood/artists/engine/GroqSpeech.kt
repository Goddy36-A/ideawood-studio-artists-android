package com.ideawood.artists.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional cloud speech recognition: Whisper large-v3 hosted by Groq (free plan, own API key entered in the app).
 * Only used when the person turns it on; the audio piece is sent over HTTPS and word + segment times come back.
 * Call from a background job (it blocks on the network).
 */
class GroqSpeech(private val settings: Settings) {
    companion object {
        const val MODEL = "whisper-large-v3"
        const val URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    }

    /** [fatal] = the key is wrong or missing: tell the person instead of quietly using the slower on-phone engine. */
    class Failure(message: String, val fatal: Boolean = false) : Exception(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Returns Groq's verbose_json text for one audio piece (16 kHz mono WAV, a few MB). */
    suspend fun transcribe(wav: File, language: String, prompt: String): String = withContext(Dispatchers.IO) {
        val key = settings.groqKey
        if (key.isBlank()) throw Failure("No Groq key entered (Song tab).", fatal = true)
        var waited = false
        while (true) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", MODEL)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("timestamp_granularities[]", "word")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .apply {
                    if (language.isNotBlank() && language != "auto") addFormDataPart("language", language)
                    if (prompt.isNotBlank()) addFormDataPart("prompt", prompt.trim().take(400))
                }
                .addFormDataPart("file", "chunk.wav", wav.asRequestBody("audio/wav".toMediaType()))
                .build()
            val req = Request.Builder().url(URL)
                .header("Authorization", "Bearer $key")
                .header("User-Agent", "IdeawoodStudio-Android")
                .post(body).build()
            var pauseMs = 0L
            try {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    when {
                        resp.isSuccessful -> return@withContext text
                        resp.code == 401 || resp.code == 403 ->
                            throw Failure("Groq did not accept the key (${resp.code}). Check it in the Song tab.", fatal = true)
                        resp.code == 429 -> {
                            val retry = resp.header("retry-after")?.toDoubleOrNull()
                            if (!waited && retry != null && retry <= 20) { waited = true; pauseMs = ((retry + 1) * 1000).toLong() }
                            else throw Failure("Groq's free limit was reached (it allows about 2 hours of audio per hour).")
                        }
                        else -> throw Failure("Groq refused the request (${resp.code}) ${errorText(text)}".trim())
                    }
                }
            } catch (e: IOException) {
                throw Failure("No connection to Groq (${e.message ?: "network problem"}).")
            }
            delay(pauseMs)          // only reached after a short rate-limit answer: wait as told, try once more
        }
        throw IllegalStateException()
    }

    private fun errorText(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message", "")?.take(160) ?: body.take(120)
    } catch (_: Exception) { body.take(120) }
}
