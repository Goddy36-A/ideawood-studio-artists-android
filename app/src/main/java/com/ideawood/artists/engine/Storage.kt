package com.ideawood.artists.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.ideawood.artists.core.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Files in/out of the phone: import picked media, save videos to the Gallery, text files to Downloads, projects. */
class Storage(private val ctx: Context, private val paths: AppPaths) {
    fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "file"
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun safeName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
        val stem = name.substringBeforeLast('.', name).filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40).ifEmpty { "file" }
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }

    /** Copy a picked file (content://) into app storage so ffmpeg can open it by path. */
    suspend fun importMedia(uri: Uri, prefix: String): File = withContext(Dispatchers.IO) {
        val dest = File(paths.media, "${prefix}_${System.currentTimeMillis()}_${safeName(displayName(uri))}")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        dest
    }

    suspend fun saveVideoToGallery(file: File, name: String): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/IdeawoodStudio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("Could not create the video file.")
        resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }

    suspend fun saveTextToDownloads(name: String, mime: String, text: String): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IdeawoodStudio")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("Could not save the file.")
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        uri
    }

    // ----------------------------------------------------------------------- the working project (autosaved, .iwlyric JSON)
    private fun current() = File(paths.projects, "current.iwlyric")

    fun saveProject(p: Project) {
        try { current().writeText(p.toJson().toString()) } catch (_: Exception) { }
    }

    fun loadProject(): Project? = try {
        current().takeIf { it.isFile }?.let { Project.fromJson(JSONObject(it.readText())) }
    } catch (_: Exception) { null }

    fun exportProjectText(p: Project): String = p.toJson().toString(1)
}
