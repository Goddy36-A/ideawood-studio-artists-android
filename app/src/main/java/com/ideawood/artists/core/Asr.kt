package com.ideawood.artists.core

import org.json.JSONObject
import java.security.MessageDigest

/** Port of the whisper.cpp JSON parser from asr.py (layout of `whisper-cli -ojf`). */
object Asr {
    private val SPECIAL = Regex("^(\\[_[^\\]]*\\]|<\\|.*\\|>)$")
    private val NONSPEECH = Regex("^\\s*[\\[\\(\\*\u266A\u266B].*[\\]\\)\\*\u266A\u266B]\\s*$")

    val MODELS: Map<String, ModelInfo> = linkedMapOf(
        "small.en" to ModelInfo("Small, English only (466 MB) - most accurate", "ggml-small.en.bin", 466, false),
        "small.en-q5_1" to ModelInfo("Small, English only, compact (181 MB) - lighter on RAM", "ggml-small.en-q5_1.bin", 181, false),
        "base.en" to ModelInfo("Base, English only (142 MB) - good balance", "ggml-base.en.bin", 142, false),
        "tiny.en" to ModelInfo("Tiny, English only (75 MB) - fastest", "ggml-tiny.en.bin", 75, false),
        "small" to ModelInfo("Small, 99 languages (466 MB)", "ggml-small.bin", 466, true),
        "base" to ModelInfo("Base, 99 languages (142 MB)", "ggml-base.bin", 142, true),
    )
    const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/%s"
    const val DEFAULT_MODEL = "small.en"

    data class ModelInfo(val label: String, val file: String, val approxMb: Int, val multilingual: Boolean)

    val LANGUAGES: Map<String, String> = linkedMapOf(
        "Auto-detect" to "auto", "English" to "en", "Swahili" to "sw", "French" to "fr", "Spanish" to "es",
        "Portuguese" to "pt", "German" to "de", "Italian" to "it", "Arabic" to "ar", "Hindi" to "hi", "Chinese" to "zh",
        "Japanese" to "ja", "Korean" to "ko", "Russian" to "ru", "Turkish" to "tr", "Dutch" to "nl", "Polish" to "pl",
        "Ukrainian" to "uk", "Indonesian" to "id", "Afrikaans" to "af", "Amharic" to "am", "Yoruba" to "yo",
        "Shona" to "sn", "Somali" to "so", "Lingala" to "ln", "Hausa" to "ha", "Bengali" to "bn", "Urdu" to "ur",
        "Vietnamese" to "vi", "Thai" to "th", "Greek" to "el",
    )

    fun parseWhisperJson(data: JSONObject, hideNonspeech: Boolean = true): Transcript {
        val tr = Transcript(language = data.optJSONObject("result")?.optString("language", "") ?: "")
        val segs = data.optJSONArray("transcription") ?: return tr
        for (si in 0 until segs.length()) {
            val seg = segs.getJSONObject(si)
            val off = seg.optJSONObject("offsets")
            val s0 = (off?.optDouble("from", 0.0) ?: 0.0) / 1000.0
            val s1 = (off?.optDouble("to", 0.0) ?: 0.0) / 1000.0
            val text = seg.optString("text", "").trim()
            if (text.isEmpty() || (hideNonspeech && NONSPEECH.matches(text))) continue
            val words = mutableListOf<Word>()
            val toks = seg.optJSONArray("tokens")
            if (toks != null) for (ti in 0 until toks.length()) {
                val tok = toks.getJSONObject(ti)
                val t = tok.optString("text", "")
                if (t.isEmpty() || SPECIAL.matches(t.trim())) continue
                val o = tok.optJSONObject("offsets")
                var a: Double? = null
                var b: Double? = null
                if (o != null && o.has("from") && o.has("to")) { a = o.getDouble("from") / 1000.0; b = o.getDouble("to") / 1000.0 }
                if (t.startsWith(" ") || words.isEmpty()) {
                    words.add(Word(a ?: -1.0, b ?: -1.0, t.trim()))
                } else {                                      // sub-word piece or punctuation: glue to the previous word
                    val w = words.last()
                    w.text += t
                    if (b != null) w.end = b
                    if (w.start < 0 && a != null) w.start = a
                }
            }
            val kept = words.filter { it.text.trim().isNotEmpty() }.toMutableList()
            repairWordTimes(kept, s0, s1)
            tr.segments.add(Segment(s0, maxOf(s1, s0 + 0.2), text, kept))
        }
        return tr
    }

    private class RawSeg(val s0: Double, val s1: Double, val text: String, val words: MutableList<Word> = mutableListOf())

    /** Parses Groq's verbose_json (segments + top-level word list) into the same Transcript the local engine produces. */
    fun parseGroqJson(data: JSONObject, hideNonspeech: Boolean = true): Transcript {
        val name = data.optString("language", "").trim()
        val code = LANGUAGES.entries.firstOrNull { it.value != "auto" && it.key.equals(name, ignoreCase = true) }?.value
            ?: (if (name.length == 2) name.lowercase() else "")
        val tr = Transcript(language = code)
        val segs = data.optJSONArray("segments") ?: return tr
        val raws = ArrayList<RawSeg>()
        for (i in 0 until segs.length()) {
            val sg = segs.getJSONObject(i)
            raws.add(RawSeg(sg.optDouble("start", 0.0), sg.optDouble("end", 0.0), sg.optString("text", "").trim()))
        }
        if (raws.isEmpty()) return tr
        val ws = data.optJSONArray("words")
        if (ws != null) {
            var j = 0
            for (k in 0 until ws.length()) {
                val w = ws.getJSONObject(k)
                val t = w.optString("word", "").trim()
                if (t.isEmpty()) continue
                val a = w.optDouble("start", -1.0)
                val b = w.optDouble("end", -1.0)
                val mid = if (a >= 0 && b >= 0) (a + b) / 2 else a
                while (j < raws.size - 1 && mid >= raws[j + 1].s0) j++     // a word belongs to the segment it falls in
                raws[j].words.add(Word(a, b, t))
            }
        }
        for (r in raws) {
            if (r.text.isEmpty() || (hideNonspeech && NONSPEECH.matches(r.text))) continue
            val kept = r.words.toMutableList()
            repairWordTimes(kept, r.s0, r.s1)
            tr.segments.add(Segment(r.s0, maxOf(r.s1, r.s0 + 0.2), r.text, kept))
        }
        return tr
    }

    internal fun repairWordTimes(words: MutableList<Word>, s0: Double, s1: Double) {
        val n = words.size
        if (n == 0) return
        if (words.all { it.start < 0 || it.end < 0 }) {
            val total = words.sumOf { maxOf(it.text.length, 1) }.toDouble()
            var t = s0
            for (w in words) {
                val d = (s1 - s0) * maxOf(w.text.length, 1) / total
                w.start = t; w.end = t + d
                t += d
            }
            return
        }
        var prevEnd = s0
        for ((i, w) in words.withIndex()) {
            if (w.start < 0) w.start = prevEnd
            if (w.end < 0 || w.end < w.start) {
                val nxt = words.subList(i + 1, n).firstOrNull { it.start >= 0 }?.start ?: s1
                w.end = maxOf(w.start + 0.05, minOf(nxt, s1))
            }
            if (w.start < prevEnd) w.start = maxOf(w.start, prevEnd - 0.0001)
            prevEnd = maxOf(prevEnd, w.end)
        }
    }
}

/** Port of ideawood_artists/scenes.py: one background scene per stanza (8-24 s each). */
object Scenes {
    val PALETTES = listOf("#141E30" to "#243B55", "#42275A" to "#734B6D", "#0F2027" to "#2C5364", "#B24592" to "#F15F79",
        "#134E5E" to "#71B280", "#232526" to "#414345", "#3A1C71" to "#D76D77", "#1D4350" to "#A43931")

    private fun key(lines: List<LyricLine>): String {
        val words = lines.flatMap { l -> Py.split(l.text).map { Lyrics.normWord(it) } }.joinToString(" ")
        val md = MessageDigest.getInstance("MD5").digest(words.toByteArray(Charsets.UTF_8))
        return md.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it.toInt() and 0xFF) }.substring(0, 10)
    }

    private fun splitGroups(lines: List<LyricLine>, maxLen: Double): MutableList<MutableList<LyricLine>> {
        val out = mutableListOf<MutableList<LyricLine>>()
        for (ln in lines) {
            if (out.isNotEmpty() && out.last()[0].stanza == ln.stanza && ln.end - out.last()[0].start <= maxLen) {
                out.last().add(ln)
            } else out.add(mutableListOf(ln))
        }
        return out
    }

    private fun palette(i: Int) = PALETTES[i % PALETTES.size].let { mutableListOf(it.first, it.second) }

    fun planScenes(lines: List<LyricLine>, duration0: Double, minLen: Double = 8.0, maxLen: Double = 24.0): MutableList<Scene> {
        val timed = lines.filter { it.timed }.sortedBy { it.start }
        var duration = duration0
        if (duration <= 0) duration = if (timed.isNotEmpty()) timed.last().end + 2.0 else 30.0
        if (timed.isEmpty()) {
            val n = maxOf(1, Py.round(duration / 15.0))
            return (0 until n).map { i -> Scene(duration * i / n, duration * (i + 1) / n, key = "t$i", colors = palette(i)) }.toMutableList()
        }
        val groups = mutableListOf<MutableList<LyricLine>>()
        for (g in splitGroups(timed, maxLen)) {
            val span = g.last().end - g.first().start
            if (groups.isNotEmpty() && span < minLen && (g.last().end - groups.last()[0].start) <= maxLen) {
                groups.last().addAll(g)
            } else if (groups.isNotEmpty() && (groups.last().last().end - groups.last()[0].start) < minLen * 0.6 &&
                (g.last().end - groups.last()[0].start) <= maxLen) {
                groups.last().addAll(g)
            } else groups.add(g.toMutableList())
        }
        val scenes = mutableListOf<Scene>()
        val palIdx = LinkedHashMap<String, Int>()
        for ((i, g) in groups.withIndex()) {
            val k = key(g)
            if (!palIdx.containsKey(k)) palIdx[k] = palIdx.size
            val a = if (i == 0) 0.0 else (groups[i - 1].last().end + g[0].start) / 2
            val b = if (i == groups.size - 1) duration else (g.last().end + groups[i + 1][0].start) / 2
            val label = Py.split(g[0].text).take(5).joinToString(" ")
            scenes.add(Scene(a, maxOf(b, a + 0.5), key = k, label = label, colors = palette(palIdx.getValue(k))))
        }
        return scenes
    }

    fun replanKeepMedia(old: List<Scene>, new: MutableList<Scene>): MutableList<Scene> {
        val byKey = LinkedHashMap<String, Scene>()
        for (s in old) if (s.kind != "gradient") byKey[s.key] = s
        for ((i, s) in new.withIndex()) {
            val src = byKey[s.key] ?: old.getOrNull(i)?.takeIf { it.kind != "gradient" && Math.abs(it.start - s.start) < 3.0 }
            if (src != null) { s.kind = src.kind; s.path = src.path; s.query = src.query; s.source = src.source; s.credit = src.credit }
        }
        return new
    }

    fun shareByKey(scenes: List<Scene>) {
        val first = LinkedHashMap<String, Scene>()
        for (s in scenes) if (s.kind != "gradient" && !first.containsKey(s.key)) first[s.key] = s
        for (s in scenes) {
            val f = first[s.key]
            if (f != null && s.kind == "gradient" && s !== f) {
                s.kind = f.kind; s.path = f.path; s.query = f.query; s.source = f.source; s.credit = f.credit
            }
        }
    }
}
