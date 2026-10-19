package com.ideawood.artists.engine

import android.content.Context
import android.os.StatFs
import com.ideawood.artists.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RenderOutput(val file: File, val seconds: Double)

/** The long-running steps: transcribe + align, render the video, draw a preview frame. */
class Pipeline(private val ctx: Context, private val paths: AppPaths, private val models: ModelManager) {
    private val whisper = WhisperRunner(ctx, paths)

    /** Choose the song: read its length and make sure it has sound. */
    suspend fun loadAudio(p: Project, file: File, originalName: String) {
        val info = MediaTools.probe(file.path)
        if (!info.hasAudio || info.duration <= 0) throw IllegalStateException("This file has no audio I can read. Choose an MP3, WAV, M4A, FLAC or a video file.")
        p.audio = file.path
        p.duration = info.duration
        if (p.title.isBlank()) p.title = Syncer.titleFromFileName(originalName)
    }

    /** Offline speech recognition + alignment (same steps as the desktop app). */
    suspend fun autoSync(p: Project, modelKey: String, language: String, onStatus: (String) -> Unit, onProgress: (Double) -> Unit,
                         cancelled: () -> Boolean): Syncer.Report {
        if (p.audio.isBlank()) throw IllegalStateException("Choose the song first.")
        val (_, model) = models.best(modelKey) ?: throw IllegalStateException("Download the speech model first (Song tab).")
        val work = paths.newWorkDir("asr")
        try {
            onStatus("Preparing the audio...")
            val wav = File(work, "audio.wav")
            val r = MediaTools.run(RenderPlan.extractAudioArgs(p.audio, wav.path), p.duration, { onProgress(it * 0.1) }, cancelled)
            if (r.cancelled) throw java.util.concurrent.CancellationException("Cancelled")
            if (!r.ok || !wav.exists()) throw IllegalStateException("Could not read audio from this file. ${r.log.takeLast(200)}")
            onStatus("Recognising speech on this phone...")
            val tr = whisper.transcribe(model, wav, language, Syncer.hint(p.lyricsText), { onProgress(10 + it * 0.9) }, cancelled)
            return withContext(Dispatchers.Default) { Syncer.applyTranscript(p, tr) }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun assFor(p: Project, work: File, preview: Boolean, frameShort: Int? = null): Pair<File, Pair<Int, Int>> {
        val short = frameShort ?: if (preview) 480 else (QUALITIES[p.quality] ?: 720)
        val (w, h) = frameSize(p.fmt, short)
        val credit = if (p.credit) "Lyric video made with Ideawood Studio for Artists" else ""
        val f = File(work, "lyrics.ass")
        f.writeText(AssLyrics.buildAss(p.lines, p.style, w, h, p.duration, p.title, p.artist, credit), Charsets.UTF_8)
        return f to (w to h)
    }

    suspend fun render(p: Project, preview: Boolean, onStatus: (String) -> Unit, onProgress: (Double) -> Unit,
                       cancelled: () -> Boolean): RenderOutput {
        if (p.audio.isBlank() || !File(p.audio).isFile) throw IllegalStateException("Please choose the song's audio file first.")
        val timed = p.lines.filter { it.timed }
        if (timed.isEmpty()) throw IllegalStateException("There are no timed lyrics yet. Sync the lyrics first.")
        paths.installFonts()
        val work = paths.newWorkDir("render")
        try {
            onStatus("Preparing the lyrics and fonts...")
            val (assFile, _) = withContext(Dispatchers.Default) { assFor(p, work, preview) }
            val t0 = if (preview) maxOf(0.0, timed.minOf { it.start } - 4.0) else 0.0
            val out = File(paths.out, "lyric_${System.currentTimeMillis()}.mp4")
            val short = if (preview) 480 else (QUALITIES[p.quality] ?: 720)
            val estBytes = ((MEDIACODEC_BITRATE[short] ?: 12_000_000) / 8.0 * p.duration * 1.3).toLong()
            if (!preview && StatFs(ctx.cacheDir.path).availableBytes < estBytes + 50_000_000) {
                throw IllegalStateException("Not enough free storage for this export. Free up some space or choose a lower quality.")
            }
            val encoders = listOf(RenderPlan.Encoder.MEDIACODEC, RenderPlan.Encoder.MPEG4)
            var last = "unknown error"
            for ((i, enc) in encoders.withIndex()) {
                out.delete()
                val built = RenderPlan.buildCommand(p, out.path, assFile.path, paths.fonts.path, enc, preview, t0, if (preview) 22.0 else null)
                onStatus(if (preview) "Rendering the preview clip..." else if (i == 0) "Rendering your lyric video..." else "Hardware encoder unavailable - using compatible mode...")
                val r = MediaTools.run(built.args, built.duration, onProgress, cancelled)
                if (r.cancelled || cancelled()) throw java.util.concurrent.CancellationException("Cancelled")
                if (r.ok && out.length() > 0) { onProgress(100.0); return RenderOutput(out, built.duration) }
                last = r.log
            }
            throw IllegalStateException("Rendering failed.\n" + last.takeLast(400))
        } finally {
            work.deleteRecursively()
        }
    }

    /** ONE frame at time [t] for the live look preview. Returns the PNG file. */
    suspend fun previewFrame(p: Project, t: Double): File {
        paths.installFonts()
        val work = paths.newWorkDir("frame")
        try {
            val (assFile, _) = withContext(Dispatchers.Default) { assFor(p, work, true, 540) }
            val png = File(paths.out, "frame_${System.currentTimeMillis()}.png")
            val args = RenderPlan.buildPreviewFrame(p, t, png.path, assFile.path, paths.fonts.path)
            val r = MediaTools.run(args, 1.0, {}, { false })
            if (!r.ok || !png.exists()) throw IllegalStateException("Could not draw the preview frame.\n" + r.log.takeLast(300))
            return png
        } finally {
            work.deleteRecursively()
        }
    }
}
