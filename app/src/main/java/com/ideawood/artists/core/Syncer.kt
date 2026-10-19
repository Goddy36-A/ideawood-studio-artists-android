package com.ideawood.artists.core

/** Port of ideawood_artists/syncer.py: "give my lyrics their timing". Pure logic, no Android. */
object Syncer {
    class Report(val mode: String, val heard: Int, val lines: Int, val match: Double)

    /** First words of the lyrics nudge Whisper toward the right spelling/vocabulary. */
    fun hint(lyricsText: String): String = Py.split(lyricsText.replace("\n", " ")).take(45).joinToString(" ")

    /** Title guessed from a file name: "my_song-final.mp3" -> "My Song Final". */
    fun titleFromFileName(name: String): String {
        val base = name.substringBeforeLast('.', name)
        return Py.title(base.replace("_", " ").replace("-", " ").trim())
    }

    fun matchRate(lines: List<LyricLine>, tr: Transcript): Double {
        val a = lines.flatMap { l -> Py.split(l.text).map { Lyrics.normWord(it) } }.filter { it.isNotEmpty() }
        val b = tr.segments.flatMap { s -> s.words.map { Lyrics.normWord(it.text) } }.filter { it.isNotEmpty() }
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return SeqMatcher(a, b).matchingBlocks().sumOf { it.size }.toDouble() / a.size
    }

    /** Fit the pasted (or recognised) lyrics to a finished transcript. Throws with a user-friendly message. */
    fun applyTranscript(p: Project, tr: Transcript): Report {
        val heard = tr.segments.sumOf { it.words.size }
        val pasted = Lyrics.parseLyrics(p.lyricsText)
        val lines: MutableList<LyricLine>
        val report: Report
        if (pasted.isNotEmpty()) {
            lines = Lyrics.alignLyrics(pasted, tr, p.duration)
            report = Report("aligned", heard, lines.size, matchRate(pasted, tr))
        } else {
            lines = Lyrics.linesFromTranscript(tr)
            report = Report("transcribed", heard, lines.size, 1.0)
        }
        if (lines.isEmpty()) throw IllegalStateException("No singing or speech was recognised. Try 'Tap to sync', or a different language.")
        p.lines = Lyrics.finalizeTiming(lines, p.duration)
        p.scenes = Scenes.replanKeepMedia(p.scenes, Scenes.planScenes(p.lines, p.duration))
        return report
    }

    /** Fallback: no recognition possible. Lines are spread over the song; fix them in the timing table. */
    fun spreadEvenly(p: Project) {
        val lines = Lyrics.parseLyrics(p.lyricsText)
        if (lines.isEmpty()) throw IllegalStateException("Paste the lyrics first.")
        val lead = minOf(6.0, p.duration * 0.05)
        p.lines = Lyrics.finalizeTiming(Lyrics.distributeEvenly(lines, maxOf(p.duration - 2.0, 5.0), lead = lead), p.duration)
        p.scenes = Scenes.replanKeepMedia(p.scenes, Scenes.planScenes(p.lines, p.duration))
    }

    /** Tap-to-sync result: starts[i] is when the artist tapped for line i. */
    fun applyTapTimes(p: Project, starts: List<Double>, lastEnd: Double? = null) {
        val lines = if (p.lines.isNotEmpty()) p.lines else Lyrics.parseLyrics(p.lyricsText)
        val n = minOf(lines.size, starts.size)
        for (i in 0 until n) {
            val ln = lines[i]
            ln.start = starts[i]
            val nxt = if (i + 1 < n) starts[i + 1] else null
            val est = maxOf(1.2, 0.38 * Py.split(ln.text).size)
            var end = ln.start + est
            if (nxt != null) end = minOf(end, nxt - 0.05) else if (lastEnd != null && lastEnd != 0.0) end = maxOf(end, lastEnd)
            ln.end = maxOf(end, ln.start + 0.5)
            ln.words = Lyrics.evenWords(ln.text, ln.start, ln.end)
        }
        val head = Lyrics.finalizeTiming(lines.subList(0, n).toMutableList(), p.duration)
        p.lines = (head + lines.subList(n, lines.size)).toMutableList()
        p.scenes = Scenes.replanKeepMedia(p.scenes, Scenes.planScenes(p.lines.filter { it.timed }, p.duration))
    }
}
