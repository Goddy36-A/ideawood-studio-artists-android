package com.ideawood.artists.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ideawood.artists.core.*
import com.ideawood.artists.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val paths = AppPaths(app)
    val storage = Storage(app, paths)
    private val settings = Settings(app)
    private val cloud = CloudVisuals(settings)
    val models = ModelManager(app, paths)
    private val pipeline = Pipeline(app, paths, models)
    private val main = Handler(Looper.getMainLooper())

    var project by mutableStateOf(storage.loadProject() ?: Project())
        private set
    /** Bumped after every edit so screens that read the (mutable) project recompose. */
    var rev by mutableIntStateOf(0)
        private set
    /** Bumped only when whole lists change (sync, split, merge...) so the timing rows rebuild their text fields. */
    var structRev by mutableIntStateOf(0)
        private set

    var message by mutableStateOf<String?>(null)
    var modelKey by mutableStateOf(Asr.DEFAULT_MODEL)
    var modelProgress by mutableStateOf<Float?>(null)
    var syncReport by mutableStateOf("")
    var previewImage by mutableStateOf<Bitmap?>(null)
    var previewBusy by mutableStateOf(false)
    var previewClip by mutableStateOf<File?>(null)
    var savedVideo by mutableStateOf<Uri?>(null)
    var playingAt by mutableStateOf(-1.0)
    var visualsKey by mutableStateOf(settings.visualsKey)
        private set
    var visualsMedia by mutableStateOf("image")
    var speechFast by mutableStateOf(settings.speechFast)
        private set
    var speechReport by mutableStateOf<String?>(null)
    var speechTesting by mutableStateOf(false)

    val job = JobRunner.state
    private var downloadJob: Job? = null
    @Volatile private var downloadCancel = false
    private var player: MediaPlayer? = null

    init {
        CrashLog.install(app)
        CrashLog.read(app)?.let { message = "Last crash: $it" }
        viewModelScope.launch(Dispatchers.IO) { paths.installFonts(); paths.cleanWork() }
    }

    private fun onMain(f: () -> Unit) { main.post(f) }

    fun touch(structural: Boolean = false) {
        rev++
        if (structural) structRev++
        viewModelScope.launch(Dispatchers.IO) { storage.saveProject(project) }
    }

    fun edit(f: (Project) -> Unit) { f(project); touch() }

    private fun copyOfProject(): Project = Project.fromJson(JSONObject(project.toJson().toString()))

    // ----------------------------------------------------------------------- song
    fun pickAudio(uri: Uri) {
        viewModelScope.launch {
            try {
                message = "Reading the song..."
                val name = storage.displayName(uri)
                val f = storage.importMedia(uri, "song")
                val p = project
                pipeline.loadAudio(p, f, name)
                message = "Song loaded (${p.duration.toInt() / 60}:${String.format(java.util.Locale.ROOT, "%02d", p.duration.toInt() % 60)})"
                touch()
            } catch (e: Throwable) {
                // Throwable (not just Exception) so native-library problems (UnsatisfiedLinkError etc.) show up as a message instead of closing the app
                if (e is java.util.concurrent.CancellationException) throw e
                CrashLog.save(getApplication(), "pickAudio", e)
                message = "Could not open that file: ${e.javaClass.simpleName}: ${e.message ?: ""}".take(300)
            }
        }
    }

    fun downloadModel(key: String) {
        if (downloadJob?.isActive == true) return
        downloadCancel = false
        modelProgress = 0f
        downloadJob = viewModelScope.launch {
            try {
                models.download(key, { pr -> onMain { modelProgress = pr.toFloat() } }, { downloadCancel })
                message = "Speech model ready."
            } catch (e: java.util.concurrent.CancellationException) { message = "Download cancelled (it will resume next time)."
            } catch (e: Exception) { message = e.message ?: "Download failed."
            } finally { modelProgress = null }
        }
    }

    fun cancelDownload() { downloadCancel = true }

    fun importModel(key: String, uri: Uri) {
        viewModelScope.launch {
            try { modelProgress = 0f; models.importFile(key, uri); message = "Speech model imported." }
            catch (e: Exception) { message = e.message ?: "Could not import the model." }
            finally { modelProgress = null }
        }
    }

    // ----------------------------------------------------------------------- sync
    fun autoSync() {
        val work = copyOfProject()
        val key = modelKey
        val ok = JobRunner.submit(getApplication(), "sync", "Syncing lyrics") { ctl ->
            val rep = pipeline.autoSync(work, key, work.language, ctl::status, ctl::progress, ctl::cancelled)
            onMain {
                project = work
                syncReport = if (rep.mode == "aligned")
                    "Heard ${rep.heard} words; ${(rep.match * 100).toInt()}% of your lyrics matched exactly. ${rep.lines} lines timed. Check the Timing tab."
                else "Wrote ${rep.lines} lines from what was heard. Edit them in the Timing tab."
                touch(true)
            }
        }
        if (!ok) message = "Another job is already running."
    }

    fun spreadEvenly() {
        try { Syncer.spreadEvenly(project); syncReport = "Lines spread over the song. Fix the times in the Timing tab."; touch(true) }
        catch (e: Exception) { message = e.message }
    }

    fun applyTaps(starts: List<Double>, lastEnd: Double) {
        project.lines = Lyrics.parseLyrics(project.lyricsText)
        Syncer.applyTapTimes(project, starts, lastEnd)
        syncReport = "Tap timing applied to ${starts.size} lines."
        touch(true)
    }

    // ----------------------------------------------------------------------- timing table
    fun setLineText(i: Int, text: String) { project.lines.getOrNull(i)?.let { Lyrics.retextLine(it, text); touch() } }
    fun setLineTimes(i: Int, start: Double, end: Double) { project.lines.getOrNull(i)?.let { if (end > start) { Lyrics.setTimes(it, start, end); touch() } } }
    fun shiftAll(delta: Double) { Lyrics.shiftAll(project.lines, delta); touch(true) }
    fun mergeNext(i: Int) { Lyrics.mergeWithNext(project.lines, i); replan(); touch(true) }
    fun splitLine(i: Int) {
        val n = Py.split(project.lines.getOrNull(i)?.text ?: "").size
        if (n >= 2) { Lyrics.splitLine(project.lines, i, n / 2); replan(); touch(true) }
    }
    fun deleteLine(i: Int) { if (i in project.lines.indices) { project.lines.removeAt(i); replan(); touch(true) } }
    fun replan() { project.scenes = Scenes.replanKeepMedia(project.scenes, Scenes.planScenes(project.lines.filter { it.timed }, project.duration)) }

    fun exportLrc(enhanced: Boolean) = saveText((project.title.ifBlank { "lyrics" }) + ".lrc", "text/plain",
        Lyrics.toLrc(project.lines, project.title, project.artist, enhanced))
    fun exportSrt() = saveText((project.title.ifBlank { "lyrics" }) + ".srt", "application/x-subrip", Lyrics.toSrt(Lyrics.toCues(project.lines)))
    fun exportProject() = saveText((project.title.ifBlank { "project" }) + ".iwlyric", "application/json", storage.exportProjectText(project))

    private fun saveText(name: String, mime: String, text: String) {
        viewModelScope.launch {
            try { storage.saveTextToDownloads(name.replace(Regex("[^A-Za-z0-9._ -]"), "_"), mime, text); message = "Saved $name to Downloads/IdeawoodStudio" }
            catch (e: Exception) { message = e.message ?: "Could not save the file." }
        }
    }

    fun importLrc(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().readText() }
                val lines = Lyrics.parseLrc(text, project.duration)
                if (lines.isEmpty()) { message = "No timed lines found in that LRC file."; return@launch }
                project.lines = lines
                project.lyricsText = Lyrics.linesToText(lines)
                replan(); touch(true); message = "Imported ${lines.size} lines."
            } catch (e: Exception) { message = e.message ?: "Could not read that file." }
        }
    }

    // ----------------------------------------------------------------------- playback (check timing)
    fun play(fromSec: Double) {
        try {
            stopPlay()
            if (project.audio.isBlank()) return
            player = MediaPlayer().apply {
                setDataSource(project.audio); prepare(); seekTo((fromSec * 1000).toInt()); start()
                setOnCompletionListener { onMain { playingAt = -1.0 } }
            }
            playingAt = fromSec
        } catch (e: Exception) { message = "Could not play the song." }
    }
    fun stopPlay() { try { player?.release() } catch (_: Exception) {}; player = null; playingAt = -1.0 }
    fun playerPositionSec(): Double = try { (player?.currentPosition ?: 0) / 1000.0 } catch (_: Exception) { 0.0 }

    // ----------------------------------------------------------------------- look
    fun applyPreset(name: String) { PRESETS[name]?.let { project.style = it.copy(); touch(); } }
    fun styleEdit(f: (LyricStyle) -> Unit) { f(project.style); touch() }

    fun refreshPreview(t: Double) {
        if (previewBusy) return
        previewBusy = true
        val work = copyOfProject()
        viewModelScope.launch {
            try {
                val png = pipeline.previewFrame(work, t)
                val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(png.path).also { png.delete() } }
                previewImage = bmp
            } catch (e: Exception) { message = e.message ?: "Could not draw the preview." }
            finally { previewBusy = false }
        }
    }

    // ----------------------------------------------------------------------- visuals
    fun setSceneMedia(i: Int, uri: Uri, mime: String?) {
        viewModelScope.launch {
            try {
                val f = storage.importMedia(uri, "scene")
                val sc = project.scenes.getOrNull(i) ?: return@launch
                sc.kind = if ((mime ?: "").startsWith("video")) "video" else "image"
                sc.path = f.path; sc.source = "mine"
                Scenes.shareByKey(project.scenes)
                touch()
            } catch (e: Exception) { message = e.message ?: "Could not use that file." }
        }
    }

    fun clearScene(i: Int) {
        project.scenes.getOrNull(i)?.let { it.kind = "gradient"; it.path = ""; it.source = "auto"; touch() }
    }

    fun setLogo(uri: Uri?) {
        if (uri == null) { project.logo = ""; touch(); return }
        viewModelScope.launch {
            try { project.logo = storage.importMedia(uri, "logo").path; touch() }
            catch (e: Exception) { message = e.message ?: "Could not use that file." }
        }
    }

    fun setSpeechFast(v: Boolean) { settings.speechFast = v; speechFast = v }

    fun testSpeech() {
        if (speechTesting) return
        speechTesting = true
        viewModelScope.launch {
            speechReport = try { pipeline.speechTest(modelKey) } catch (e: Throwable) { "Test failed: ${e.message ?: e.javaClass.simpleName}" }
            speechTesting = false
        }
    }

    // ----------------------------------------------------------------------- AI backgrounds (lyric-visual endpoint)
    fun saveVisualsKey(k: String) { settings.visualsKey = k; visualsKey = settings.visualsKey }

    /** One request for the whole song (one entry per stanza), then download each picture into app storage. */
    fun generateVisuals() {
        if (settings.visualsKey.isBlank()) { message = "Enter your visuals key first."; return }
        val plan = VisualsApi.plan(project)
        if (plan.isEmpty()) { message = if (project.scenes.isEmpty()) "Sync the lyrics first." else "Every scene already has your own picture/video."; return }
        val media = visualsMedia
        val ok = JobRunner.submit(getApplication(), "visuals", "Finding backgrounds") { ctl ->
            ctl.status("Asking the visuals service (a free server can take up to a minute to wake up)...")
            ctl.progress(2.0)
            val items = cloud.fetch(plan.map { it.text }, media)
            if (items.isEmpty()) throw IllegalStateException("The service found nothing for these lyrics.")
            val results = HashMap<String, Applied>()
            var lastError = ""
            for ((i, pl) in plan.withIndex()) {
                if (ctl.cancelled()) throw java.util.concurrent.CancellationException("Cancelled")
                ctl.status("Downloading ${i + 1} of ${plan.size}...")
                ctl.progress(15.0 + 85.0 * i / plan.size)
                try {
                    results[pl.key] = cloud.download(VisualsApi.pick(items, i), File(paths.media, "ai_${System.currentTimeMillis()}_$i"))
                } catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName }
            }
            if (results.isEmpty()) throw IllegalStateException("None of the pictures could be downloaded. $lastError".trim())
            val texts = plan.associate { it.key to it.text }
            onMain {
                val n = VisualsApi.apply(project, results, texts)
                Scenes.shareByKey(project.scenes)
                touch()
                message = "Backgrounds added to $n scene${if (n == 1) "" else "s"}" +
                    (if (results.size < plan.size) " (${plan.size - results.size} could not be downloaded)" else "")
                viewModelScope.launch(Dispatchers.IO) { pruneAiMedia() }
            }
        }
        if (!ok) message = "Another job is already running."
    }

    /** Delete downloaded AI pictures that no scene uses any more. */
    private fun pruneAiMedia() {
        val used = project.scenes.map { it.path }.toSet()
        paths.media.listFiles()?.filter { it.name.startsWith("ai_") && it.path !in used }?.forEach { it.delete() }
    }

    // ----------------------------------------------------------------------- export
    fun render(preview: Boolean) {
        val work = copyOfProject()
        val title = if (preview) "Rendering preview" else "Rendering lyric video"
        savedVideo = null
        val ok = JobRunner.submit(getApplication(), if (preview) "preview" else "render", title) { ctl ->
            val out = pipeline.render(work, preview, ctl::status, ctl::progress, ctl::cancelled)
            if (preview) {
                onMain { previewClip = out.file }
            } else {
                ctl.status("Saving to your Gallery...")
                val name = (work.title.ifBlank { "lyric-video" }).replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "lyric-video" } + ".mp4"
                val uri = storage.saveVideoToGallery(out.file, name)
                out.file.delete()
                onMain { savedVideo = uri; message = "Saved to Movies/IdeawoodStudio" }
            }
        }
        if (!ok) message = "Another job is already running."
    }

    override fun onCleared() { stopPlay(); super.onCleared() }
}
