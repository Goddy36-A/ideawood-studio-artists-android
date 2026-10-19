package com.ideawood.artists.core

import kotlin.math.ceil

/** Port of ideawood_artists/ass_lyrics.py: timed lines + LyricStyle -> ASS script (libass draws it inside ffmpeg). */
object AssLyrics {
    private val READABLE = setOf("Poppins", "Poppins ExtraBold", "Archivo Black", "DM Serif Display", "Righteous",
        "Abril Fatface", "Alfa Slab One")
    const val LINE_H = 1.22
    const val LEAD = 0.10
    const val HOLD = 0.45

    private val FONT_WIDTH = mapOf("Anton" to 0.44, "Bebas Neue" to 0.40, "Staatliches" to 0.44, "Great Vibes" to 0.36,
        "Lobster" to 0.50, "Pacifico" to 0.56, "Monoton" to 0.72, "Bungee" to 0.68, "Archivo Black" to 0.62,
        "Alfa Slab One" to 0.66, "Righteous" to 0.56, "Poppins" to 0.58, "Poppins ExtraBold" to 0.60,
        "Abril Fatface" to 0.52, "DM Serif Display" to 0.50)
    private val CAPS_FONTS = setOf("Anton", "Bebas Neue", "Staatliches", "Bungee", "Monoton")

    // ----------------------------------------------------------------------- tiny helpers (asr.py)
    fun assTime(sec: Double): String {
        var cs = Py.round(maxOf(sec, 0.0) * 100)
        val h = cs / 360000; cs %= 360000
        val m = cs / 6000; cs %= 6000
        val s = cs / 100; cs %= 100
        return "$h:${two(m)}:${two(s)}.${two(cs)}"
    }

    private fun two(v: Int) = if (v < 10) "0$v" else "$v"

    fun assEscape(s: String): String = s.replace("\\", "\\\\").replace("{", "(").replace("}", ")")

    // ----------------------------------------------------------------------- geometry
    fun fontPx(w: Int, h: Int, size: Double): Int {
        val base = minOf(h.toDouble(), w * (if (w >= h) 0.9 else 1.15))
        return maxOf(18, (base * size).toInt())
    }

    fun autoMaxChars(w: Int, h: Int, fs: Int, uppercase: Boolean = false, font: String = ""): Int {
        var perChar = fs * (FONT_WIDTH[font] ?: 0.56)
        if (uppercase && font !in CAPS_FONTS) perChar *= 1.12
        return maxOf(12.0, minOf(48.0, (w * 0.84) / perChar)).toInt()
    }

    fun bgr(hex: String?): String {
        var c = (if (hex.isNullOrEmpty()) "#FFFFFF" else hex).trimStart('#')
        c = (c + "000000").substring(0, 6)
        return c.substring(4, 6) + c.substring(2, 4) + c.substring(0, 2)
    }

    fun assColor(hex: String, alpha: Double = 0.0): String =
        "&H" + Py.hex2((maxOf(0.0, minOf(1.0, alpha)) * 255).toInt()) + bgr(hex)

    fun tagColor(n: Int, hex: String) = "\\${n}c&H${bgr(hex)}&"

    fun wrapBalanced(words: List<String>, maxChars: Int, maxLines: Int = 3): List<Int> {
        val textLen = words.sumOf { it.length } + maxOf(words.size - 1, 0)
        val nLines = minOf(maxLines, maxOf(1, ceil(textLen.toDouble() / maxChars).toInt()))
        if (nLines == 1) return emptyList()
        val target = textLen.toDouble() / nLines
        val breaks = mutableListOf<Int>()
        var cur = 0
        for (i in 0 until words.size - 1) {
            val w = words[i]
            cur += w.length + (if (cur != 0) 1 else 0)
            val remaining = nLines - breaks.size - 1
            if (remaining > 0 && cur >= target * 0.92 && (words.size - i - 1) >= remaining) {
                breaks.add(i); cur = 0
            }
            if (breaks.size == nLines - 1) break
        }
        return breaks
    }

    private class Geom(val w: Int, val h: Int, val st: LyricStyle) {
        val fs = fontPx(w, h, st.size)
        val maxChars = if (st.maxChars != 0) st.maxChars else autoMaxChars(w, h, fs, st.uppercase, st.font)
        val x = w / 2
        val scale = h / 1080.0
        fun blockH(nLines: Int, fsOverride: Int? = null) = nLines * (fsOverride ?: fs) * LINE_H
        fun topY(nLines: Int): Double {
            val bh = blockH(nLines)
            val y = st.posY * h - bh / 2
            return maxOf(h * 0.04, minOf(y, h * 0.96 - bh))
        }
    }

    private class Prepared(val toks: List<String>, val breaks: Set<Int>, val times: List<Pair<Double, Double>>)

    private fun prepare(line: LyricLine, st: LyricStyle, g: Geom): Prepared {
        val text = if (st.uppercase) line.text.uppercase() else line.text
        val toks = Py.split(text)
        if (toks.isEmpty()) return Prepared(emptyList(), emptySet(), emptyList())
        val ws = if (line.words.size == toks.size) line.words else Lyrics.evenWords(line.text, line.start, line.end)
        return Prepared(toks, wrapBalanced(toks, g.maxChars).toSet(), ws.map { Pair(it.start, it.end) })
    }

    private fun animTags(st: LyricStyle, x: Int, y: Double, fs: Int, fadeOutMs: Int): String {
        val ms = maxOf(0, st.animMs)
        val base = "\\an8\\pos($x,${Py.fmt(y, 0)})"
        if (st.animIn == "none") return base
        val fad = "\\fad($ms,$fadeOutMs)"
        return when (st.animIn) {
            "pop" -> base + fad + "\\fscx82\\fscy82\\t(0,$ms,\\fscx100\\fscy100)"
            "slide" -> "\\an8\\move($x,${Py.fmt(y + fs * 0.55, 0)},$x,${Py.fmt(y, 0)},0,$ms)" + fad
            "blur" -> base + fad + "\\blur14\\t(0,$ms,\\blur0)"
            else -> base + fad
        }
    }

    private fun karaokeText(toks: List<String>, breaks: Set<Int>, times: List<Pair<Double, Double>>, st: LyricStyle,
                            showStart: Double): String {
        val sb = StringBuilder()
        if (st.highlight == "none") {
            for ((i, t) in toks.withIndex()) {
                sb.append(assEscape(t)).append(if (i in breaks) "\\N" else if (i < toks.size - 1) " " else "")
            }
            return sb.toString()
        }
        val tag = if (st.highlight == "sweep") "\\kf" else "\\k"
        var cursor = showStart
        for ((i, t) in toks.withIndex()) {
            var s = times[i].first
            val e = times[i].second
            s = maxOf(s, cursor)
            val pre = Py.round((s - cursor) * 100)
            val dur = maxOf(1, Py.round(maxOf(e - s, 0.05) * 100))
            sb.append(if (pre > 0) "{\\k$pre}" else "").append("{$tag$dur}").append(assEscape(t))
            sb.append(if (i in breaks) "\\N" else if (i < toks.size - 1) " " else "")
            cursor = s + dur / 100.0
        }
        return sb.toString()
    }

    private fun styleRow(name: String, st: LyricStyle, fs: Int, g: Geom, unsung: String, sung: String, outlinePx: Double,
                         shadowPx: Double, box: Boolean, bold: Boolean? = null, italic: Boolean? = null, font: String? = null): String {
        val b = bold ?: st.bold
        val it = italic ?: st.italic
        val borderStyle = if (box) 3 else 1
        val outCol = if (box) assColor(st.boxColor, 1 - st.boxAlpha) else assColor(st.outlineColor)
        val pad = if (box) fs * 0.22 else outlinePx
        val ml = (g.w * 0.07).toInt()
        val shadow = if (box) 0.0 else shadowPx
        return "Style: $name,${font ?: st.font},$fs,${assColor(sung)},${assColor(unsung)},$outCol," +
            "${assColor("#000000", 0.35)},${if (b) -1 else 0},${if (it) -1 else 0},0,0,100,100," +
            "${Py.fmt(st.spacing * g.scale, 1)},0,$borderStyle,${Py.fmt(pad, 1)},${Py.fmt(shadow, 1)},8,$ml,$ml,0,1"
    }

    // ----------------------------------------------------------------------- the script
    fun buildAss(lines: List<LyricLine>, st: LyricStyle, w: Int, h: Int, duration: Double, title: String = "",
                 artist: String = "", credit: String = ""): String {
        val g = Geom(w, h, st)
        val fs = g.fs
        val outlinePx = st.outline * fs
        val shadowPx = st.shadow * fs
        val highlight = st.highlight != "none"
        val unsung = st.colorUnsung
        val sung = if (highlight) st.colorSung else st.colorUnsung

        val nextFs = maxOf(14, (fs * 0.62).toInt())
        val titleFs = (fs * 1.25).toInt()
        val rows = listOf(
            styleRow("Main", st, fs, g, unsung, sung, outlinePx, shadowPx, st.box),
            styleRow("Next", st, nextFs, g, st.colorUnsung, st.colorUnsung, outlinePx * 0.6, shadowPx * 0.6, false,
                bold = false, font = if (st.font in READABLE) st.font else "Poppins"),
            styleRow("Dots", st, (fs * 1.5).toInt(), g, st.colorUnsung, st.colorSung, outlinePx * 0.8, shadowPx, false,
                bold = true, font = "Poppins"),
            styleRow("Title", st, titleFs, g, st.colorUnsung, st.colorUnsung, outlinePx, shadowPx, false),
            styleRow("Artist", st, (titleFs * 0.5).toInt(), g, st.colorSung, st.colorSung, outlinePx * 0.7, shadowPx, false),
            styleRow("Credit", st, maxOf(14, (fs * 0.34).toInt()), g, "#FFFFFF", "#FFFFFF", 1.2, 0.0, false,
                bold = false, font = "Poppins"),
        )
        val head = "[Script Info]\nScriptType: v4.00+\nPlayResX: $w\nPlayResY: $h\nWrapStyle: 2\n" +
            "ScaledBorderAndShadow: yes\n\n[V4+ Styles]\n" +
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, " +
            "Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, " +
            "MarginL, MarginR, MarginV, Encoding\n" + rows.joinToString("\n") + "\n\n" +
            "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"

        val timed = lines.filter { it.timed && it.text.trim().isNotEmpty() }.sortedBy { it.start }
        val ev = mutableListOf<String>()

        fun add(layer: Int, a: Double, b: Double, style: String, text: String) {
            if (b - a >= 0.04) ev.add("Dialogue: $layer,${assTime(a)},${assTime(b)},$style,,0,0,0,,$text")
        }

        var prevEvEnd = 0.0
        for ((i, ln) in timed.withIndex()) {
            val p = prepare(ln, st, g)
            if (p.toks.isEmpty()) continue
            val nLines = p.breaks.size + 1
            val nxt = if (i + 1 < timed.size) timed[i + 1] else null
            val room = if (nxt != null) nxt.start - ln.end else HOLD
            var end = ln.end + maxOf(0.0, minOf(HOLD, room - LEAD - 0.03))
            if (duration != 0.0) end = minOf(end, duration)
            val start = minOf(maxOf(ln.start - LEAD, prevEvEnd, 0.0), ln.start)
            prevEvEnd = end
            val y = g.topY(nLines)
            val fadeOut = minOf(260.0, maxOf(60.0, (end - ln.end) * 1000)).toInt()
            val anim = animTags(st, g.x, y, fs, fadeOut)
            val text = karaokeText(p.toks, p.breaks, p.times.map { Pair(maxOf(it.first, start), it.second) }, st, start)
            if (st.glow) {
                val glowTags = "$anim\\bord${Py.fmt(fs * 0.20, 1)}\\blur${Py.fmt(fs * 0.20, 1)}${tagColor(3, st.glowColor)}" +
                    "\\1a&HFF&\\3a&H30&\\shad0"
                val glowTxt = p.toks.withIndex().joinToString("") { (k, t) -> assEscape(t) + (if (k in p.breaks) "\\N" else " ") }.trimEnd()
                add(0, start, end, "Main", "{$glowTags}$glowTxt")
            }
            add(1, start, end, "Main", "{$anim}$text")

            if (st.showNext && nxt != null && nxt.start - ln.end < 4.0) {
                val ntoks = Py.split(if (st.uppercase) nxt.text.uppercase() else nxt.text)
                if (ntoks.isNotEmpty()) {
                    val nbreaks = wrapBalanced(ntoks, (g.maxChars * fs.toDouble() / nextFs).toInt()).toSet()
                    val ny = y + g.blockH(nLines) + fs * 0.18
                    val ntext = ntoks.withIndex().joinToString("") { (k, t) -> assEscape(t) + (if (k in nbreaks) "\\N" else " ") }.trimEnd()
                    val aHex = Py.hex2(((1 - st.nextAlpha) * 255).toInt())
                    val nend = minOf(nxt.start, end)
                    add(1, start, nend, "Next",
                        "{\\an8\\pos(${g.x},${Py.fmt(ny, 0)})\\fad(${maxOf(120, st.animMs / 2)},120)\\1a&H$aHex&" +
                            "\\3a&H$aHex&\\4a&HFF&}$ntext")
                }
            }
        }

        // instrumental countdown dots + title card
        val firstStart = if (timed.isNotEmpty()) timed[0].start else duration
        val titleOn = st.titleCard && (title.isNotEmpty() || artist.isNotEmpty()) && firstStart >= 2.8
        val titleEnd = if (titleOn) minOf(st.titleSec, firstStart - 0.4) else 0.0
        if (titleOn) {
            val ty = h * 0.40
            val t0 = 0.35
            val fade = "\\fad(500,500)"
            if (title.isNotEmpty()) {
                add(1, t0, titleEnd, "Title", "{\\an8\\pos(${g.x},${Py.fmt(ty, 0)})$fade}${assEscape(if (st.uppercase) title.uppercase() else title)}")
            }
            if (artist.isNotEmpty()) {
                add(1, t0 + 0.25, titleEnd, "Artist",
                    "{\\an8\\pos(${g.x},${Py.fmt(ty + titleFs * LINE_H, 0)})$fade}${assEscape(artist)}")
            }
        }
        if (st.instrumentalDots) {
            for ((a, b) in Lyrics.gaps(timed, duration)) {
                if (b >= duration - 0.01 || b - a < 5.5) continue
                var d0 = maxOf(a + 1.0, b - 3.4)
                val d1 = b - 0.15
                if (titleOn && d0 < titleEnd) d0 = titleEnd + 0.2
                if (d1 - d0 < 1.2) continue
                val third = maxOf(0.2, (d1 - d0) / 3.0)
                val cs = Py.round(third * 100)
                val dots = (0 until 3).joinToString("") { "{\\kf$cs}\u2022 " }.trimEnd()
                val y = g.topY(1)
                add(1, d0, d1, "Dots", "{\\an8\\pos(${g.x},${Py.fmt(y, 0)})\\fad(300,300)}$dots")
            }
        }
        if (credit.isNotEmpty() && duration > 6) {
            add(2, maxOf(duration - 5.0, 0.0), duration, "Credit",
                "{\\an2\\pos(${g.x},${Py.fmt(h * 0.965, 0)})\\fad(400,400)\\1a&H40&}${assEscape(credit)}")
        }
        return head + ev.joinToString("\n") + "\n"
    }
}
