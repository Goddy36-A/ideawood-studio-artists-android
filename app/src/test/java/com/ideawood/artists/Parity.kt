package com.ideawood.artists

import com.ideawood.artists.core.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compares the Kotlin port with golden.json produced by tools/gen_golden.py from the desktop Python app.
 * Returns a list of mismatch descriptions (empty = identical behaviour).
 */
object Parity {
    private val failures = mutableListOf<String>()
    private var checks = 0

    private fun eq(what: String, expected: Any?, actual: Any?) {
        checks++
        if (!deepEq(expected, actual)) {
            failures.add("$what\n   expected: ${short(expected)}\n   actual:   ${short(actual)}")
        }
    }

    private fun short(v: Any?): String = v.toString().let { if (it.length > 400) it.take(400) + "..." else it }

    private fun deepEq(a: Any?, b: Any?): Boolean {
        if (a is Number && b is Number) return Math.abs(a.toDouble() - b.toDouble()) < 1e-9
        if (a is List<*> && b is List<*>) return a.size == b.size && a.indices.all { deepEq(a[it], b[it]) }
        if (a is Map<*, *> && b is Map<*, *>) return a.keys == b.keys && a.keys.all { deepEq(a[it], b[it]) }
        return a == b
    }

    private fun conv(v: Any?): Any? = when (v) {
        is JSONObject -> v.keySet().associateWith { conv(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { conv(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    private fun lineMap(l: LyricLine): Map<String, Any?> = mapOf(
        "text" to l.text, "start" to l.start, "end" to l.end, "section" to l.section, "stanza" to l.stanza,
        "words" to l.words.map { listOf(it.start, it.end, it.text) },
    )

    private fun lines(label: String, golden: JSONArray, actual: List<LyricLine>) {
        eq("$label: count", golden.length(), actual.size)
        for (i in 0 until minOf(golden.length(), actual.size)) eq("$label[$i]", conv(golden.get(i)), lineMap(actual[i]))
    }

    private fun trMap(tr: Transcript) = mapOf("language" to tr.language, "segments" to tr.segments.map { s ->
        mapOf("start" to s.start, "end" to s.end, "text" to s.text, "words" to s.words.map { listOf(it.start, it.end, it.text) })
    })

    private fun firstDiff(a: String, b: String): String {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        val from = maxOf(0, i - 60)
        return "first difference at char $i:\n     expected: …${a.substring(from, minOf(a.length, i + 80)).replace("\n", "⏎")}\n     actual:   …${b.substring(from, minOf(b.length, i + 80)).replace("\n", "⏎")}"
    }

    private fun str(what: String, expected: String, actual: String) {
        checks++
        if (expected != actual) failures.add("$what: ${firstDiff(expected, actual)}")
    }

    fun run(goldenText: String): Pair<Int, List<String>> {
        failures.clear(); checks = 0
        val g = JSONObject(goldenText)
        val duration = g.getDouble("duration")
        val lyricsText = g.getString("lyrics_text")

        // --- whisper JSON -> transcript
        val tr = Asr.parseWhisperJson(g.getJSONObject("whisper_json"))
        eq("parsed transcript", conv(g.getJSONObject("parsed_transcript")), trMap(tr))

        // --- lyric parsing / alignment / fallbacks
        val pasted = Lyrics.parseLyrics(lyricsText)
        lines("parse_lyrics", g.getJSONArray("parsed_lines"), pasted)
        val aligned = Lyrics.alignLyrics(Lyrics.parseLyrics(lyricsText), tr, duration)
        lines("align_lyrics", g.getJSONArray("aligned"), aligned)
        lines("lines_from_transcript", g.getJSONArray("from_transcript"), Lyrics.linesFromTranscript(tr))
        val even = Lyrics.finalizeTiming(Lyrics.distributeEvenly(Lyrics.parseLyrics(lyricsText), duration, lead = 6.0), duration)
        lines("distribute_evenly", g.getJSONArray("even"), even)

        // --- editing operations
        val ed = aligned.map { LyricLine(it.text, it.start, it.end, it.words.map { w -> Word(w.start, w.end, w.text) }.toMutableList(), it.section, it.stanza) }.toMutableList()
        Lyrics.retextLine(ed[3], "Brand new   words here for this line")
        Lyrics.setTimes(ed[5], ed[5].start + 0.5, ed[5].end + 1.5)
        Lyrics.splitLine(ed, 7, 3)
        Lyrics.mergeWithNext(ed, 1)
        Lyrics.shiftAll(ed, 0.35)
        lines("edit ops", g.getJSONArray("edited"), ed)
        eq("gaps", conv(g.getJSONArray("gaps")), Lyrics.gaps(aligned, duration).map { listOf(it.first, it.second) })

        // --- LRC / SRT
        str("to_lrc", g.getString("lrc"), Lyrics.toLrc(aligned, "My Song", "Some Artist"))
        str("to_lrc enhanced", g.getString("lrc_enhanced"), Lyrics.toLrc(aligned, "My Song", "Some Artist", enhanced = true))
        str("to_srt", g.getString("srt"), Lyrics.toSrt(Lyrics.toCues(aligned)))
        lines("parse_lrc", g.getJSONArray("parsed_lrc"), Lyrics.parseLrc(g.getString("lrc"), duration))
        lines("parse_lrc enhanced", g.getJSONArray("parsed_lrc_enhanced"), Lyrics.parseLrc(g.getString("lrc_enhanced"), duration))
        str("lines_to_text", g.getString("lines_to_text"), Lyrics.linesToText(pasted))

        // --- syncer (auto-sync result, transcript-only, even spread, tap-to-sync)
        fun rep(r: Syncer.Report) = mapOf("mode" to r.mode, "heard" to r.heard, "lines" to r.lines, "match" to r.match)
        fun scn(l: List<Scene>) = l.map { listOf(it.start, it.end, it.key) }
        val sa = g.getJSONObject("sync_aligned")
        val sp = Project().also { it.audio = "x.mp3"; it.duration = duration; it.lyricsText = lyricsText }
        eq("sync aligned: report", conv(sa.getJSONObject("report")), rep(Syncer.applyTranscript(sp, tr)))
        lines("sync aligned", sa.getJSONArray("lines"), sp.lines)
        eq("sync aligned: scenes", conv(sa.getJSONArray("scenes")), scn(sp.scenes))
        val st2 = g.getJSONObject("sync_transcribed")
        val sp2 = Project().also { it.audio = "x.mp3"; it.duration = duration }
        eq("sync transcribed: report", conv(st2.getJSONObject("report")), rep(Syncer.applyTranscript(sp2, tr)))
        lines("sync transcribed", st2.getJSONArray("lines"), sp2.lines)
        val sp3 = Project().also { it.audio = "x.mp3"; it.duration = duration; it.lyricsText = lyricsText }
        Syncer.spreadEvenly(sp3)
        lines("sync spread", g.getJSONArray("sync_spread"), sp3.lines)
        val tapsG = g.getJSONObject("sync_taps")
        val sp4 = Project().also { it.audio = "x.mp3"; it.duration = duration; it.lyricsText = lyricsText; it.lines = Lyrics.parseLyrics(lyricsText) }
        Syncer.applyTapTimes(sp4, conv(tapsG.getJSONArray("taps")).let { l -> (l as List<*>).map { (it as Number).toDouble() } }, duration - 3)
        lines("sync taps", tapsG.getJSONArray("lines"), sp4.lines)
        eq("sync taps: scenes", conv(tapsG.getJSONArray("scenes")), scn(sp4.scenes))
        str("hint", g.getString("hint"), Syncer.hint(lyricsText))
        eq("match rate", g.getDouble("match_rate"), Syncer.matchRate(Lyrics.parseLyrics(lyricsText), tr))
        str("title from file name", "My Song Final Version", Syncer.titleFromFileName(g.getString("title_from_name") + ".mp3"))

        // --- scenes
        fun sceneMap(s: Scene, withLabel: Boolean) = mutableMapOf<String, Any?>("start" to s.start, "end" to s.end, "key" to s.key,
            "colors" to s.colors).also { if (withLabel) it["label"] = s.label }
        eq("plan_scenes", conv(g.getJSONArray("scenes")), Scenes.planScenes(aligned, duration).map { sceneMap(it, true) })
        eq("plan_scenes (no lines)", conv(g.getJSONArray("scenes_no_lines")), Scenes.planScenes(emptyList(), 100.0).map { sceneMap(it, false) })

        // --- ASS scripts
        val shapes = mapOf("16x9" to (1280 to 720), "9x16" to (720 to 1280), "1x1" to (720 to 720), "4x5" to (720 to 900))
        val ass = g.getJSONObject("ass")
        for (key in ass.keys()) {
            val parts = key.split("|")
            val st = if (parts[0] == "noTitle") PRESETS.getValue("Neon Night") else PRESETS.getValue(parts[0])
            val (w, h) = shapes.getValue(parts[1])
            val noTitle = parts[0] == "noTitle"
            val got = AssLyrics.buildAss(aligned, st, w, h, duration, if (noTitle) "" else "My Song", if (noTitle) "" else "Some Artist",
                if (noTitle) "" else "Lyric video made with Ideawood Studio for Artists")
            str("ASS $key", ass.getString(key), got)
        }

        // --- render commands (the exact ffmpeg filter graph)
        val cmds = g.getJSONObject("cmds")
        val projects = g.getJSONObject("projects")
        for (key in cmds.keys()) {
            val preview = key.endsWith("|preview")
            val pkey = key.removeSuffix("|preview")
            val p = Project.fromJson(projects.getJSONObject(pkey))
            // the golden project was built with 3-decimal lines; rebuild scenes exactly as saved
            val exp = cmds.getJSONObject(key)
            val built = RenderPlan.buildCommand(p, if (preview) "/tmp/iwtest/prev.mp4" else outFor(pkey), "lyrics.ass", "fonts",
                RenderPlan.Encoder.X264, preview = preview, t0 = if (preview) 5.0 else 0.0, span = if (preview) 22.0 else null)
            eq("cmd $key: args", conv(exp.getJSONArray("args")), built.args)
            eq("cmd $key: duration", exp.getDouble("dur"), built.duration)
        }

        // --- frame sizes
        val fs = g.getJSONObject("frame_sizes")
        val fmts = FORMATS.keys.toList()
        for (key in fs.keys()) {
            val (fi, s) = key.split("|")
            eq("frame_size $key", conv(fs.getJSONArray(key)), frameSize(fmts[fi.toInt()], s.toInt()).toList())
        }

        // --- project JSON round trip is lossless for what the renderer needs
        val p0 = Project.fromJson(projects.getJSONObject(projects.keys().next()))
        val p1 = Project.fromJson(JSONObject(p0.toJson().toString()))
        eq("project json round trip", conv(p0.toJson()), conv(p1.toJson()))
        return Pair(checks, failures.toList())
    }

    private fun outFor(pkey: String) = when (pkey) {
        "notrans" -> "/tmp/iwtest/o2.mp4"
        "single" -> "/tmp/iwtest/o3.mp4"
        else -> "/tmp/iwtest/out.mp4"
    }
}
