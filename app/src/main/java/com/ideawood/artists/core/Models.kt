package com.ideawood.artists.core

import org.json.JSONArray
import org.json.JSONObject

// --------------------------------------------------------------------------- transcript model
class Word(var start: Double, var end: Double, var text: String)

class Segment(var start: Double, var end: Double, var text: String, var words: MutableList<Word> = mutableListOf())

class Transcript(var language: String = "", var segments: MutableList<Segment> = mutableListOf()) {
    fun text(): String = segments.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

class Cue(var start: Double, var end: Double, var text: String, var words: MutableList<Word> = mutableListOf())

// --------------------------------------------------------------------------- lyrics
class LyricLine(
    var text: String,
    var start: Double = -1.0,            // < 0 means "not timed yet"
    var end: Double = -1.0,
    var words: MutableList<Word> = mutableListOf(),
    var section: String = "",
    var stanza: Int = 0,
) {
    val timed: Boolean get() = start >= 0 && end > start

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("text", text)
        o.put("start", round3(start)); o.put("end", round3(end))
        o.put("section", section); o.put("stanza", stanza)
        val ws = JSONArray()
        for (w in words) ws.put(JSONArray().put(round3(w.start)).put(round3(w.end)).put(w.text))
        o.put("words", ws)
        return o
    }

    companion object {
        private fun round3(v: Double) = Math.rint(v * 1000.0) / 1000.0

        fun fromJson(d: JSONObject): LyricLine {
            val ws = mutableListOf<Word>()
            val arr = d.optJSONArray("words")
            if (arr != null) for (i in 0 until arr.length()) {
                val w = arr.getJSONArray(i)
                ws.add(Word(w.getDouble(0), w.getDouble(1), w.getString(2)))
            }
            return LyricLine(d.optString("text", ""), d.optDouble("start", -1.0), d.optDouble("end", -1.0), ws,
                d.optString("section", ""), d.optInt("stanza", 0))
        }
    }
}

// --------------------------------------------------------------------------- look
val GRADES: Map<String, String> = linkedMapOf(
    "None" to "",
    "Cinematic" to "colorbalance=rs=-0.06:bs=0.07:rh=0.07:bh=-0.07,eq=contrast=1.08:saturation=1.12",
    "Warm film" to "colorbalance=rs=0.07:bs=-0.07:rm=0.05:bm=-0.05,eq=saturation=1.1",
    "Cool night" to "colorbalance=rs=-0.06:bs=0.09:rm=-0.04:bm=0.06,eq=contrast=1.05",
    "Vibrant" to "eq=saturation=1.4:contrast=1.08",
    "Faded vintage" to "curves=preset=vintage",
    "Black & white" to "hue=s=0,eq=contrast=1.12",
)
val TRANSITIONS = listOf("fade", "dissolve", "fadeblack", "smoothleft", "slideleft", "wipeleft", "circleopen", "radial", "none")
val ANIMATIONS = listOf("fade", "pop", "slide", "blur", "none")
val HIGHLIGHTS = listOf("sweep", "word", "none")
val VIZ_MODES = listOf("none", "wave", "bars")

data class LyricStyle(
    var name: String = "Karaoke Bold",
    var font: String = "Poppins",
    var bold: Boolean = true,
    var italic: Boolean = false,
    var uppercase: Boolean = false,
    var size: Double = 0.075,
    var spacing: Double = 0.0,
    var posY: Double = 0.5,
    var maxChars: Int = 0,
    var colorUnsung: String = "#FFFFFF",
    var colorSung: String = "#FFD84D",
    var outlineColor: String = "#000000",
    var outline: Double = 0.05,
    var shadow: Double = 0.03,
    var glow: Boolean = false,
    var glowColor: String = "#00E5FF",
    var box: Boolean = false,
    var boxColor: String = "#000000",
    var boxAlpha: Double = 0.5,
    var animIn: String = "fade",
    var animMs: Int = 280,
    var highlight: String = "sweep",
    var showNext: Boolean = true,
    var nextAlpha: Double = 0.55,
    var instrumentalDots: Boolean = true,
    var bgDim: Double = 0.30,
    var bgBlur: Double = 0.0,
    var vignette: Boolean = true,
    var grain: Double = 0.0,
    var grade: String = "Cinematic",
    var kenBurns: Boolean = true,
    var transition: String = "fade",
    var transitionSec: Double = 1.0,
    var viz: String = "none",
    var vizColor: String = "#FFFFFF",
    var titleCard: Boolean = true,
    var titleSec: Double = 4.0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("font", font); put("bold", bold); put("italic", italic); put("uppercase", uppercase)
        put("size", size); put("spacing", spacing); put("pos_y", posY); put("max_chars", maxChars)
        put("color_unsung", colorUnsung); put("color_sung", colorSung); put("outline_color", outlineColor)
        put("outline", outline); put("shadow", shadow); put("glow", glow); put("glow_color", glowColor)
        put("box", box); put("box_color", boxColor); put("box_alpha", boxAlpha)
        put("anim_in", animIn); put("anim_ms", animMs); put("highlight", highlight)
        put("show_next", showNext); put("next_alpha", nextAlpha); put("instrumental_dots", instrumentalDots)
        put("bg_dim", bgDim); put("bg_blur", bgBlur); put("vignette", vignette); put("grain", grain)
        put("grade", grade); put("ken_burns", kenBurns); put("transition", transition)
        put("transition_sec", transitionSec); put("viz", viz); put("viz_color", vizColor)
        put("title_card", titleCard); put("title_sec", titleSec)
    }

    companion object {
        fun fromJson(d: JSONObject?): LyricStyle {
            val s = LyricStyle()
            if (d == null) return s
            fun str(k: String, cur: String) = if (d.has(k)) d.getString(k) else cur
            fun dbl(k: String, cur: Double) = if (d.has(k)) d.getDouble(k) else cur
            fun int(k: String, cur: Int) = if (d.has(k)) d.getInt(k) else cur
            fun bool(k: String, cur: Boolean) = if (d.has(k)) d.getBoolean(k) else cur
            return s.copy(
                name = str("name", s.name), font = str("font", s.font), bold = bool("bold", s.bold),
                italic = bool("italic", s.italic), uppercase = bool("uppercase", s.uppercase),
                size = dbl("size", s.size), spacing = dbl("spacing", s.spacing), posY = dbl("pos_y", s.posY),
                maxChars = int("max_chars", s.maxChars), colorUnsung = str("color_unsung", s.colorUnsung),
                colorSung = str("color_sung", s.colorSung), outlineColor = str("outline_color", s.outlineColor),
                outline = dbl("outline", s.outline), shadow = dbl("shadow", s.shadow), glow = bool("glow", s.glow),
                glowColor = str("glow_color", s.glowColor), box = bool("box", s.box),
                boxColor = str("box_color", s.boxColor), boxAlpha = dbl("box_alpha", s.boxAlpha),
                animIn = str("anim_in", s.animIn), animMs = int("anim_ms", s.animMs),
                highlight = str("highlight", s.highlight), showNext = bool("show_next", s.showNext),
                nextAlpha = dbl("next_alpha", s.nextAlpha), instrumentalDots = bool("instrumental_dots", s.instrumentalDots),
                bgDim = dbl("bg_dim", s.bgDim), bgBlur = dbl("bg_blur", s.bgBlur), vignette = bool("vignette", s.vignette),
                grain = dbl("grain", s.grain), grade = str("grade", s.grade), kenBurns = bool("ken_burns", s.kenBurns),
                transition = str("transition", s.transition), transitionSec = dbl("transition_sec", s.transitionSec),
                viz = str("viz", s.viz), vizColor = str("viz_color", s.vizColor),
                titleCard = bool("title_card", s.titleCard), titleSec = dbl("title_sec", s.titleSec),
            )
        }
    }
}

const val DEFAULT_PRESET = "Karaoke Bold"

val PRESETS: Map<String, LyricStyle> = linkedMapOf(
    "Karaoke Bold" to LyricStyle(name = "Karaoke Bold"),
    "Neon Night" to LyricStyle(name = "Neon Night", font = "Righteous", bold = false, size = 0.08,
        colorUnsung = "#EAFBFF", colorSung = "#FF3DCB", outline = 0.03, shadow = 0.0, glow = true,
        glowColor = "#18E6FF", animIn = "blur", highlight = "sweep", bgDim = 0.45, grade = "Cool night",
        viz = "bars", vizColor = "#18E6FF", grain = 0.03),
    "Cinematic Gold" to LyricStyle(name = "Cinematic Gold", font = "DM Serif Display", bold = false, size = 0.07,
        colorUnsung = "#F4EBDD", colorSung = "#F2C14E", outline = 0.035, shadow = 0.05, animIn = "fade",
        animMs = 600, highlight = "sweep", bgDim = 0.40, grade = "Warm film", transition = "fadeblack",
        transitionSec = 1.4, grain = 0.04, posY = 0.78),
    "Street Poster" to LyricStyle(name = "Street Poster", font = "Anton", bold = false, uppercase = true,
        size = 0.105, colorUnsung = "#FFFFFF", colorSung = "#FF2E2E", outline = 0.04, shadow = 0.0, animIn = "pop",
        animMs = 200, highlight = "word", showNext = false, bgDim = 0.5, grade = "Black & white",
        transition = "wipeleft", transitionSec = 0.5, grain = 0.06),
    "Soul & Script" to LyricStyle(name = "Soul & Script", font = "Great Vibes", bold = false, size = 0.13,
        colorUnsung = "#FFF6EE", colorSung = "#FFB3C7", outline = 0.03, shadow = 0.05, glow = true,
        glowColor = "#FF8FB1", animIn = "fade", animMs = 700, highlight = "sweep", bgDim = 0.35,
        grade = "Warm film", transition = "dissolve", transitionSec = 1.6),
    "Retro Tape" to LyricStyle(name = "Retro Tape", font = "Bungee", bold = false, size = 0.07,
        colorUnsung = "#FFF1D0", colorSung = "#FF8A3D", outline = 0.05, shadow = 0.06, animIn = "slide",
        highlight = "word", bgDim = 0.35, grade = "Faded vintage", grain = 0.08, transition = "slideleft"),
    "Clean Minimal" to LyricStyle(name = "Clean Minimal", font = "Poppins", bold = false, size = 0.052,
        colorUnsung = "#FFFFFF", colorSung = "#FFFFFF", outline = 0.0, shadow = 0.0, box = true, boxAlpha = 0.45,
        animIn = "fade", highlight = "none", showNext = false, posY = 0.86, bgDim = 0.15, grade = "None",
        vignette = false, titleCard = false),
    "Reggae Sunrise" to LyricStyle(name = "Reggae Sunrise", font = "Staatliches", bold = false, size = 0.10,
        uppercase = true, colorUnsung = "#FFF3C4", colorSung = "#3DDC84", outline = 0.045, shadow = 0.04,
        animIn = "pop", animMs = 240, highlight = "sweep", bgDim = 0.32, grade = "Vibrant",
        transition = "smoothleft", transitionSec = 1.2, grain = 0.03),
)

// --------------------------------------------------------------------------- project
data class Format(val aw: Int, val ah: Int, val orientation: String?)

val FORMATS: Map<String, Format> = linkedMapOf(
    "Landscape 16:9 (YouTube)" to Format(16, 9, "horizontal"),
    "Portrait 9:16 (Reels / TikTok / Shorts)" to Format(9, 16, "vertical"),
    "Square 1:1" to Format(1, 1, null),
    "Portrait 4:5 (Instagram feed)" to Format(4, 5, "vertical"),
)
val QUALITIES: Map<String, Int> = linkedMapOf(
    "480p (fast draft)" to 480, "720p (HD)" to 720, "1080p (Full HD)" to 1080, "1440p (2K)" to 1440, "2160p (4K)" to 2160,
)
val CRF: Map<Int, Int> = mapOf(480 to 27, 720 to 23, 1080 to 20, 1440 to 19, 2160 to 18)
/** Hardware-encoder bitrates (bits per second) per short side - tuned to look like x264 CRF 20 on phones. */
val MEDIACODEC_BITRATE: Map<Int, Int> = mapOf(480 to 2_500_000, 720 to 6_000_000, 1080 to 12_000_000, 1440 to 20_000_000, 2160 to 40_000_000)
val LOGO_POSITIONS = listOf("Top left", "Top right", "Bottom left", "Bottom right")

fun frameSize(fmt: String, shortSide: Int): Pair<Int, Int> {
    val f = FORMATS.getValue(fmt)
    val w: Int
    val h: Int
    if (f.aw >= f.ah) {
        h = shortSide
        w = Py.round(h.toDouble() * f.aw / f.ah / 2) * 2
    } else {
        w = shortSide
        h = Py.round(w.toDouble() * f.ah / f.aw / 2) * 2
    }
    return Pair(w + (w % 2), h + (h % 2))
}

class Scene(
    var start: Double,
    var end: Double,
    var kind: String = "gradient",       // "image" | "video" | "gradient"
    var path: String = "",
    var query: String = "",
    var source: String = "auto",         // "auto" | "ai" | "mine"
    var credit: String = "",
    var key: String = "",                // identical stanzas (the chorus) share a key and therefore a background
    var colors: MutableList<String> = mutableListOf("#141E30", "#243B55"),
    var label: String = "",
) {
    val duration: Double get() = maxOf(end - start, 0.0)

    fun toJson(): JSONObject = JSONObject().apply {
        put("start", start); put("end", end); put("kind", kind); put("path", path); put("query", query)
        put("source", source); put("credit", credit); put("key", key); put("colors", JSONArray(colors)); put("label", label)
    }

    companion object {
        fun fromJson(d: JSONObject): Scene {
            val cols = mutableListOf<String>()
            val arr = d.optJSONArray("colors")
            if (arr != null) for (i in 0 until arr.length()) cols.add(arr.getString(i))
            if (cols.isEmpty()) cols.addAll(listOf("#141E30", "#243B55"))
            return Scene(d.optDouble("start", 0.0), d.optDouble("end", 0.0), d.optString("kind", "gradient"),
                d.optString("path", ""), d.optString("query", ""), d.optString("source", "auto"),
                d.optString("credit", ""), d.optString("key", ""), cols, d.optString("label", ""))
        }
    }
}

class Project(
    var audio: String = "",
    var duration: Double = 0.0,
    var title: String = "",
    var artist: String = "",
    var genre: String = "",
    var mood: String = "",
    var lyricsText: String = "",
    var lines: MutableList<LyricLine> = mutableListOf(),
    var style: LyricStyle = PRESETS.getValue(DEFAULT_PRESET).copy(),
    var scenes: MutableList<Scene> = mutableListOf(),
    var fmt: String = "Landscape 16:9 (YouTube)",
    var quality: String = "720p (HD)",
    var logo: String = "",
    var logoPos: String = "Top right",
    var logoSize: Double = 0.12,
    var credit: Boolean = false,
    var normalizeAudio: Boolean = false,
    var language: String = "auto",
) {
    val orientation: String? get() = FORMATS.getValue(fmt).orientation

    /** Same layout as the desktop app's .iwlyric file, so projects can move between PC and phone. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("audio", audio); put("duration", duration); put("title", title); put("artist", artist)
        put("genre", genre); put("mood", mood); put("lyrics_text", lyricsText)
        put("fmt", fmt); put("quality", quality); put("logo", logo); put("logo_pos", logoPos)
        put("logo_size", logoSize); put("credit", credit); put("normalize_audio", normalizeAudio)
        put("language", language)
        put("lines", JSONArray().also { a -> lines.forEach { a.put(it.toJson()) } })
        put("style", style.toJson())
        put("scenes", JSONArray().also { a -> scenes.forEach { a.put(it.toJson()) } })
        put("version", 1)
    }

    companion object {
        fun fromJson(d: JSONObject): Project {
            val p = Project()
            p.audio = d.optString("audio", ""); p.duration = d.optDouble("duration", 0.0)
            p.title = d.optString("title", ""); p.artist = d.optString("artist", "")
            p.genre = d.optString("genre", ""); p.mood = d.optString("mood", "")
            p.lyricsText = d.optString("lyrics_text", "")
            p.fmt = d.optString("fmt", p.fmt).let { if (FORMATS.containsKey(it)) it else p.fmt }
            p.quality = d.optString("quality", p.quality).let { if (QUALITIES.containsKey(it)) it else p.quality }
            p.logo = d.optString("logo", ""); p.logoPos = d.optString("logo_pos", p.logoPos)
            p.logoSize = d.optDouble("logo_size", p.logoSize); p.credit = d.optBoolean("credit", false)
            p.normalizeAudio = d.optBoolean("normalize_audio", false); p.language = d.optString("language", "auto")
            d.optJSONArray("lines")?.let { a -> for (i in 0 until a.length()) p.lines.add(LyricLine.fromJson(a.getJSONObject(i))) }
            p.style = LyricStyle.fromJson(d.optJSONObject("style"))
            d.optJSONArray("scenes")?.let { a -> for (i in 0 until a.length()) p.scenes.add(Scene.fromJson(a.getJSONObject(i))) }
            return p
        }
    }
}
