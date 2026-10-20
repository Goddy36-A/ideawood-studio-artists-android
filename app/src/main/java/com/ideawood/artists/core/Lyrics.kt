package com.ideawood.artists.core

import java.util.regex.Pattern

/** Port of ideawood_artists/lyrics.py (+ the SRT writer from asr.py). Behaviour is kept identical on purpose. */
object Lyrics {
    private val SECTION_RE = Regex(
        "^\\s*[\\[\\(]\\s*(verse|chorus|hook|bridge|intro|outro|pre[- ]?chorus|refrain|interlude|" +
            "break|drop|rap|spoken|ad[- ]?lib|instrumental)[^\\]\\)]*[\\]\\)]\\s*$",
        RegexOption.IGNORE_CASE,
    )
    // Android rejects UNICODE_CHARACTER_CLASS (there \\w is already Unicode-aware), so fall back to the plain pattern; desktop JVM keeps the flag for Python parity.
    private val NORM: Pattern = try { Pattern.compile("[^\\w']+", Pattern.UNICODE_CHARACTER_CLASS) } catch (e: IllegalArgumentException) { Pattern.compile("[^\\w']+") }
    private val BRACKETS = Regex("[\\[\\]\\(\\)]")
    private val SPLIT_CUT = Regex("[,;:\u2013\u2014]\\s")
    const val MAX_LINE_CHARS = 60

    fun normWord(w: String): String = NORM.matcher(w.lowercase().replace("\u2019", "'")).replaceAll("")

    // ----------------------------------------------------------------------- parsing
    fun parseLyrics(text: String): MutableList<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        var stanza = 0
        var section = ""
        var pendingBlank = false
        for (raw in (text).replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            val s = raw.trim()
            if (s.isEmpty()) { pendingBlank = true; continue }
            if (SECTION_RE.matches(s)) {
                section = Py.title(BRACKETS.replace(s, "").trim())
                pendingBlank = true
                continue
            }
            if (pendingBlank && lines.isNotEmpty()) stanza += 1
            pendingBlank = false
            for (piece in splitLong(s)) lines.add(LyricLine(piece, section = section, stanza = stanza))
        }
        return lines
    }

    fun splitLong(s: String, limit: Int = MAX_LINE_CHARS): List<String> {
        if (s.length <= limit) return listOf(s)
        val head = s.substring(0, minOf(s.length, limit + 1))
        var cut = SPLIT_CUT.findAll(head).maxOfOrNull { it.range.last + 1 } ?: 0
        if (cut < limit * 0.4) cut = s.lastIndexOf(' ', limit - 1)
        if (cut <= 0) cut = limit
        return listOf(s.substring(0, cut).trim()) + splitLong(s.substring(cut).trim(), limit)
    }

    // ----------------------------------------------------------------------- alignment
    private fun flatten(tr: Transcript): List<Word> {
        val out = mutableListOf<Word>()
        for (seg in tr.segments) for (w in seg.words) if (normWord(w.text).isNotEmpty()) out.add(w)
        return out
    }

    private class Tok(val li: Int, val wi: Int, val text: String)

    fun alignLyrics(lines: MutableList<LyricLine>, tr: Transcript, audioDuration: Double = 0.0): MutableList<LyricLine> {
        val heard = flatten(tr)
        val toks = mutableListOf<Tok>()
        for ((li, ln) in lines.withIndex()) {
            for ((wi, w) in Py.split(ln.text).withIndex()) if (normWord(w).isNotEmpty()) toks.add(Tok(li, wi, w))
        }
        if (toks.isEmpty()) return lines
        if (heard.isEmpty()) return distributeEvenly(lines, if (audioDuration != 0.0) audioDuration else 60.0)

        val a = toks.map { normWord(it.text) }
        val b = heard.map { normWord(it.text) }
        val t0 = arrayOfNulls<Double>(toks.size)
        val t1 = arrayOfNulls<Double>(toks.size)
        val sm = SeqMatcher(a, b)
        val blocks = sm.matchingBlocks()
        for (blk in blocks) for (k in 0 until blk.size) {
            t0[blk.a + k] = heard[blk.b + k].start
            t1[blk.a + k] = heard[blk.b + k].end
        }
        fuzzyFill(a, b, heard, t0, t1, blocks)
        interpolate(t0, t1, if (audioDuration != 0.0) audioDuration else heard.last().end + 1.0)

        val perLine = LinkedHashMap<Int, MutableList<Word>>()
        for ((k, t) in toks.withIndex()) {
            perLine.getOrPut(t.li) { mutableListOf() }.add(Word(t0[k]!!, maxOf(t1[k]!!, t0[k]!! + 0.04), t.text))
        }
        for ((li, ln) in lines.withIndex()) {
            val ws = perLine[li] ?: continue
            ln.words = ws
            ln.start = ws.first().start
            ln.end = ws.last().end
        }
        return finalizeTiming(lines, audioDuration)
    }

    private fun fuzzyFill(a: List<String>, b: List<String>, heard: List<Word>, t0: Array<Double?>, t1: Array<Double?>,
                          blocks: List<SeqMatcher.Match>) {
        for ((k, blk) in blocks.withIndex()) {
            val aLo = if (k == 0) 0 else blocks[k - 1].a + blocks[k - 1].size
            val bLo = if (k == 0) 0 else blocks[k - 1].b + blocks[k - 1].size
            val gapB = (bLo until blk.b).toList()
            if (gapB.isEmpty() || blk.a <= aLo) continue
            var j0 = 0
            for (i in aLo until blk.a) {
                var best = 0.0
                var bestJ = -1
                for (j in j0 until gapB.size) {
                    if (a[i].isNotEmpty() && b[gapB[j]].isNotEmpty()) {
                        val r = SeqMatcher(a[i].toList(), b[gapB[j]].toList()).ratio()
                        if (r > best) { best = r; bestJ = j }
                    }
                }
                if (best >= 0.6) {
                    t0[i] = heard[gapB[bestJ]].start
                    t1[i] = heard[gapB[bestJ]].end
                    j0 = bestJ + 1
                }
            }
        }
    }

    private fun interpolate(t0: Array<Double?>, t1: Array<Double?>, duration: Double) {
        val n = t0.size
        val known = (0 until n).filter { t0[it] != null }
        if (known.isEmpty()) {
            val step = duration / n
            for (i in 0 until n) { t0[i] = i * step; t1[i] = (i + 1) * step * 0.95 }
            return
        }
        val k0 = known[0]
        for (i in 0 until k0) {                              // before the first anchor
            val span = maxOf(t0[k0]!! - 0.0, 0.5)
            t0[i] = maxOf(0.0, t0[k0]!! - span * (k0 - i) / (k0 + 1))
            t1[i] = t0[i]!! + span / (k0 + 1) * 0.9
        }
        for (idx in 0 until known.size - 1) {                // between anchors
            val aI = known[idx]
            val bI = known[idx + 1]
            val gap = bI - aI - 1
            if (gap <= 0) continue
            val lo = t1[aI]!!
            var hi = t0[bI]!!
            if (hi <= lo) hi = lo + 0.25 * gap
            val step = (hi - lo) / gap
            for (k in 0 until gap) {
                t0[aI + 1 + k] = lo + step * k
                t1[aI + 1 + k] = lo + step * (k + 0.9)
            }
        }
        val last = known.last()                              // after the last anchor
        val tail = n - last - 1
        for (k in 0 until tail) {
            t0[last + 1 + k] = t1[last]!! + 0.3 * k
            t1[last + 1 + k] = t0[last + 1 + k]!! + 0.3
        }
    }

    fun distributeEvenly(lines: MutableList<LyricLine>, duration: Double, lead: Double = 0.0): MutableList<LyricLine> {
        val weights = lines.map { maxOf(it.text.length, 4) }
        val total = weights.sum().toDouble()
        var t = lead
        val span = maxOf(duration - lead, 1.0)
        for ((ln, wt) in lines.zip(weights)) {
            val d = span * wt / total
            ln.start = t; ln.end = t + d * 0.95
            ln.words = evenWords(ln.text, ln.start, ln.end)
            t += d
        }
        return lines
    }

    fun evenWords(text: String, start: Double, end: Double): MutableList<Word> {
        val ws = Py.split(text)
        if (ws.isEmpty()) return mutableListOf()
        val total = ws.sumOf { maxOf(it.length, 1) }.toDouble()
        var t = start
        val out = mutableListOf<Word>()
        for (w in ws) {
            val d = (end - start) * maxOf(w.length, 1) / total
            out.add(Word(t, t + d, w))
            t += d
        }
        return out
    }

    fun linesFromTranscript(tr: Transcript, maxChars: Int = 46): MutableList<LyricLine> {
        val out = mutableListOf<LyricLine>()
        var stanza = 0
        var lastEnd = 0.0
        for (seg in tr.segments) {
            val words = seg.words.filter { it.text.trim().isNotEmpty() }.toMutableList()
                .ifEmpty { evenWords(seg.text, seg.start, seg.end) }
            if (words.isEmpty()) continue
            if (out.isNotEmpty() && words[0].start - lastEnd > 4.0) stanza += 1
            var cur = mutableListOf<Word>()
            for (w in words) {
                cur.add(w)
                if (cur.joinToString(" ") { it.text }.length >= maxChars) {
                    out.add(lineFromWords(cur, stanza)); cur = mutableListOf()
                }
            }
            if (cur.isNotEmpty()) out.add(lineFromWords(cur, stanza))
            lastEnd = words.last().end
        }
        return finalizeTiming(out)
    }

    private fun lineFromWords(ws: List<Word>, stanza: Int) =
        LyricLine(ws.joinToString(" ") { it.text }, ws.first().start, ws.last().end, ws.toMutableList(), stanza = stanza)

    // ----------------------------------------------------------------------- timing hygiene
    fun finalizeTiming(lines: MutableList<LyricLine>, duration: Double = 0.0, minDur: Double = 0.6): MutableList<LyricLine> {
        val timed = lines.filter { it.start >= 0 }
        var prevEnd = 0.0
        for ((i, ln) in timed.withIndex()) {
            if (ln.start < prevEnd) ln.start = prevEnd
            val nxt: Double? = if (i + 1 < timed.size) timed[i + 1].start else null
            ln.end = maxOf(ln.end, ln.start + minDur)
            if (nxt != null && nxt >= 0 && ln.end > nxt) ln.end = maxOf(ln.start + 0.2, nxt - 0.02)
            if (duration != 0.0) {
                ln.end = minOf(ln.end, duration)
                ln.start = minOf(ln.start, maxOf(duration - 0.2, 0.0))
            }
            prevEnd = ln.end
            fitWords(ln)
        }
        return lines
    }

    private fun fitWords(ln: LyricLine) {
        val ws = ln.words
        if (ws.isEmpty()) return
        var prev = ln.start
        for (w in ws) {
            w.start = minOf(maxOf(w.start, prev), ln.end - 0.02)
            w.end = minOf(maxOf(w.end, w.start + 0.03), ln.end)
            prev = w.start
        }
        for (i in 0 until ws.size - 1) {
            val cand = if (ws[i].end > ws[i].start) ws[i].end else ws[i + 1].start
            ws[i].end = maxOf(ws[i].start + 0.03, minOf(cand, ws[i + 1].start))
        }
        val last = ws.last()
        last.end = maxOf(last.end, minOf(ln.end, last.start + 0.3))
    }

    fun retextLine(ln: LyricLine, text: String) {
        ln.text = text.trim()
        if (ln.timed) ln.words = evenWords(ln.text, ln.start, ln.end)
    }

    fun setTimes(ln: LyricLine, start: Double, end: Double) {
        if (ln.words.isNotEmpty() && ln.timed && ln.end > ln.start) {
            val k = (end - start) / (ln.end - ln.start)
            for (w in ln.words) {
                w.start = start + (w.start - ln.start) * k
                w.end = start + (w.end - ln.start) * k
            }
        }
        ln.start = start; ln.end = end
        if (ln.words.isEmpty()) ln.words = evenWords(ln.text, start, end)
    }

    fun shiftAll(lines: List<LyricLine>, delta: Double) {
        for (ln in lines) {
            if (ln.start < 0) continue
            ln.start = maxOf(0.0, ln.start + delta); ln.end = maxOf(0.0, ln.end + delta)
            for (w in ln.words) { w.start = maxOf(0.0, w.start + delta); w.end = maxOf(0.0, w.end + delta) }
        }
    }

    fun mergeWithNext(lines: MutableList<LyricLine>, i: Int) {
        if (i + 1 >= lines.size) return
        val a = lines[i]
        val b = lines[i + 1]
        a.text = "${a.text} ${b.text}".trim()
        a.words = (a.words + b.words).toMutableList()
        if (b.end > 0) a.end = b.end
        lines.removeAt(i + 1)
    }

    fun splitLine(lines: MutableList<LyricLine>, i: Int, atWord: Int) {
        val ln = lines[i]
        val ws = Py.split(ln.text)
        if (atWord !in 1 until ws.size) return
        val aWords: MutableList<Word>
        val bWords: MutableList<Word>
        if (ln.timed && ln.words.size == ws.size) {
            aWords = ln.words.subList(0, atWord).toMutableList(); bWords = ln.words.subList(atWord, ln.words.size).toMutableList()
        } else { aWords = mutableListOf(); bWords = mutableListOf() }
        val first = LyricLine(ws.subList(0, atWord).joinToString(" "), ln.start, ln.end, aWords, ln.section, ln.stanza)
        val second = LyricLine(ws.subList(atWord, ws.size).joinToString(" "), ln.start, ln.end, bWords, ln.section, ln.stanza)
        if (ln.timed) {
            val mid = if (aWords.isNotEmpty()) aWords.last().end else ln.start + (ln.end - ln.start) * atWord / ws.size
            first.end = maxOf(mid, ln.start + 0.2)
            second.start = minOf(maxOf(if (bWords.isNotEmpty()) bWords[0].start else mid, first.end), ln.end - 0.1)
            if (aWords.isEmpty()) {
                first.words = evenWords(first.text, first.start, first.end)
                second.words = evenWords(second.text, second.start, second.end)
            }
        }
        lines.removeAt(i)
        lines.add(i, second)
        lines.add(i, first)
    }

    /** Quiet stretches (intro, solos, outro): used for instrumental dots / the title card. */
    fun gaps(lines: List<LyricLine>, duration: Double): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var t = 0.0
        for (ln in lines.filter { it.timed }.sortedBy { it.start }) {
            if (ln.start > t) out.add(Pair(t, ln.start))
            t = maxOf(t, ln.end)
        }
        if (duration > t) out.add(Pair(t, duration))
        return out
    }

    // ----------------------------------------------------------------------- LRC / SRT
    private val LRC_TS = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val LRC_WORD = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")

    private fun tsToS(m: MatchResult): Double {
        val frac = m.groupValues[3].ifEmpty { "0" }
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() + frac.toInt() / Math.pow(10.0, frac.length.toDouble())
    }

    private class Raw(val t: Double, val text: String, val wtimes: List<Double>)

    fun parseLrc(text: String, duration: Double = 0.0): MutableList<LyricLine> {
        val raw = mutableListOf<Raw>()
        for (row in text.lines()) {
            val stamps = LRC_TS.findAll(row).toList()
            if (stamps.isEmpty()) continue
            val body = row.substring(stamps.last().range.last + 1).trim()
            val wt = LRC_WORD.findAll(body).map { tsToS(it) }.toList()
            val clean = LRC_WORD.replace(body, "").trim()
            if (clean.isEmpty()) continue
            for (st in stamps) raw.add(Raw(tsToS(st), clean, wt))
        }
        raw.sortBy { it.t }
        val out = mutableListOf<LyricLine>()
        for ((i, r) in raw.withIndex()) {
            val end = if (i + 1 < raw.size) raw[i + 1].t else (if (duration != 0.0) duration else r.t + 4.0)
            val ln = LyricLine(r.text, r.t, maxOf(minOf(end - 0.05, r.t + 8.0), r.t + 0.5))
            val ws = Py.split(r.text)
            if (r.wtimes.isNotEmpty() && r.wtimes.size == ws.size) {
                ln.words = ws.indices.map { k -> Word(r.wtimes[k], if (k + 1 < r.wtimes.size) r.wtimes[k + 1] else ln.end, ws[k]) }.toMutableList()
            } else ln.words = evenWords(r.text, ln.start, ln.end)
            out.add(ln)
        }
        var stanza = 0
        for (i in 1 until out.size) {
            if (out[i].start - out[i - 1].end > 3.0) stanza += 1
            out[i].stanza = stanza
        }
        return finalizeTiming(out, duration)
    }

    private fun stamp(t: Double, open: Char = '['): String {
        val v = maxOf(t, 0.0)
        val m = Math.floor(v / 60.0).toInt()
        val s = v - m * 60.0
        val close = if (open == '[') ']' else '>'
        return "$open${String.format(java.util.Locale.ROOT, "%02d", m)}:${Py.fmt(s, 2).padStart(5, '0')}$close"
    }

    fun toLrc(lines: List<LyricLine>, title: String = "", artist: String = "", enhanced: Boolean = false): String {
        val head = mutableListOf<String>()
        if (title.isNotEmpty()) head.add("[ti:$title]")
        if (artist.isNotEmpty()) head.add("[ar:$artist]")
        head.add("[by:Ideawood Studio for Artists]")
        val body = mutableListOf<String>()
        for (ln in lines) {
            if (!ln.timed) continue
            if (enhanced && ln.words.isNotEmpty()) {
                body.add(stamp(ln.start) + " " + ln.words.joinToString(" ") { "${stamp(it.start, '<')}${it.text}" })
            } else body.add("${stamp(ln.start)}${ln.text}")
        }
        return (head + body).joinToString("\n") + "\n"
    }

    fun toCues(lines: List<LyricLine>): List<Cue> =
        lines.filter { it.timed }.map { Cue(it.start, it.end, it.text, it.words.toMutableList()) }

    private fun srtTs(sec: Double): String {
        var ms = Py.round(maxOf(sec, 0.0) * 1000)
        val h = ms / 3_600_000; ms %= 3_600_000
        val m = ms / 60_000; ms %= 60_000
        val s = ms / 1000; ms %= 1000
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    fun toSrt(cues: List<Cue>): String =
        cues.withIndex().joinToString("") { (i, c) -> "${i + 1}\n${srtTs(c.start)} --> ${srtTs(c.end)}\n${c.text}\n\n" }

    fun linesToText(lines: List<LyricLine>): String {
        val out = mutableListOf<String>()
        var lastStanza: Int? = null
        var lastSection = ""
        for (ln in lines) {
            if (lastStanza != null && ln.stanza != lastStanza) out.add("")
            if (ln.section.isNotEmpty() && ln.section != lastSection && (lastStanza == null || ln.stanza != lastStanza)) {
                out.add("[${ln.section}]")
            }
            out.add(ln.text)
            lastStanza = ln.stanza; lastSection = ln.section
        }
        return out.joinToString("\n")
    }
}
