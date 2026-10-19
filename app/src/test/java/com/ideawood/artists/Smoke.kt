package com.ideawood.artists

import com.ideawood.artists.core.*
import org.json.JSONObject
import java.io.File

/** Writes lyrics.ass + cmd.txt (one ffmpeg argument per line) so CI can run the generated command on a real ffmpeg. */
object Smoke {
    fun generate(goldenText: String, dir: File, preset: String, fmtIndex: Int) {
        val g = JSONObject(goldenText)
        dir.mkdirs()
        File(dir, "img.jpg").writeBytes(byteArrayOf(0))   // placeholder so image scenes are planned; CI swaps in a real picture
        val p = Project()
        p.audio = "song.wav"
        p.duration = 60.0; p.title = "My Song"; p.artist = "Some Artist"
        p.style = PRESETS.getValue(preset).copy()
        p.fmt = FORMATS.keys.toList()[fmtIndex]; p.quality = "480p (fast draft)"
        val al = g.getJSONArray("aligned")
        for (i in 0 until al.length()) { val l = LyricLine.fromJson(al.getJSONObject(i)); if (l.end < 58) p.lines.add(l) }
        p.scenes = Scenes.planScenes(p.lines, p.duration)
        for ((i, s) in p.scenes.withIndex()) if (i % 2 == 1) { s.kind = "image"; s.path = File(dir, "img.jpg").path }
        val (w, h) = frameSize(p.fmt, 480)
        File(dir, "lyrics.ass").writeText(AssLyrics.buildAss(p.lines, p.style, w, h, p.duration, p.title, p.artist, ""))
        val b = RenderPlan.buildCommand(p, "out.mp4", "lyrics.ass", "fonts", RenderPlan.Encoder.X264, preview = true, t0 = 5.0, span = 14.0)
        File(dir, "cmd.txt").writeText(b.args.joinToString("\n").replace(File(dir, "img.jpg").path, "img.jpg"))
    }
}

fun main(args: Array<String>) = Smoke.generate(File(args[0]).readText(), File(args[1]), args[2], args[3].toInt())
