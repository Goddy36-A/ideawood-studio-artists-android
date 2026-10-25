package com.ideawood.artists.core

import org.json.JSONArray
import org.json.JSONObject

/** One picture/clip returned by the visuals service. */
class VisualItem(val url: String, val kind: String, val credit: String)

/** A scene's new background once downloaded. */
class Applied(val path: String, val kind: String, val credit: String)

/**
 * Pure logic for the AI-visuals endpoint (https://lyric-visual.onrender.com/v1/visuals):
 * POST {"lines":[...],"media":"image"|"video"} with header X-API-Key -> JSON array of scenes, each with a "url".
 * The parser is deliberately tolerant about field names so small server changes do not break the app.
 */
object VisualsApi {
    const val DEFAULT_BASE = "https://lyric-visual.onrender.com"
    val MEDIA = listOf("image", "video")

    fun requestBody(lines: List<String>, media: String): String =
        JSONObject().put("lines", JSONArray(lines)).put("media", if (media in MEDIA) media else "image").toString()

    private val URL_KEYS = listOf("url", "image_url", "video_url", "src", "link")
    private val CREDIT_KEYS = listOf("credit", "attribution", "author", "photographer", "by", "user")
    private val LIST_KEYS = listOf("scenes", "visuals", "results", "items", "data", "media")

    private fun kindOf(o: JSONObject, url: String): String {
        for (k in listOf("type", "media", "kind", "mime", "mime_type")) {
            val v = o.optString(k, "").lowercase()
            if (v.contains("video")) return "video"
            if (v.contains("image") || v.contains("photo")) return "image"
        }
        val path = url.substringBefore('?').lowercase()
        return if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mov") || path.endsWith(".m4v")) "video" else "image"
    }

    private fun item(el: Any?): VisualItem? {
        if (el is String) return if (el.startsWith("http")) VisualItem(el, kindOf(JSONObject(), el), "") else null
        if (el !is JSONObject) return null
        val url = URL_KEYS.firstNotNullOfOrNull { k -> el.optString(k, "").takeIf { it.startsWith("http") } } ?: return null
        val credit = CREDIT_KEYS.firstNotNullOfOrNull { k ->
            val v = el.opt(k)
            when (v) { is String -> v.takeIf { it.isNotBlank() }; is JSONObject -> v.optString("name", "").takeIf { it.isNotBlank() }; else -> null }
        } ?: ""
        return VisualItem(url, kindOf(el, url), credit.take(120))
    }

    fun parse(body: String): List<VisualItem> {
        val t = body.trim()
        val arr: JSONArray = if (t.startsWith("[")) JSONArray(t) else {
            val o = JSONObject(t)
            LIST_KEYS.firstNotNullOfOrNull { o.optJSONArray(it) } ?: JSONArray().also { a -> item(o)?.let { _ -> a.put(o) } }
        }
        return (0 until arr.length()).mapNotNull { item(arr.opt(it)) }
    }

    /** Short server error text from {"error": "..."} / {"message": "..."} (never echoes HTML pages). */
    fun errorText(body: String): String = try {
        val o = JSONObject(body.trim())
        val m = o.opt("error") ?: o.opt("message") ?: o.opt("detail")
        when (m) { is String -> m; is JSONObject -> m.optString("message", ""); else -> "" }.take(160)
    } catch (_: Exception) { "" }

    // ----------------------------------------------------------------------- scenes <-> requests
    fun keyOf(sc: Scene, index: Int): String = sc.key.ifEmpty { "i$index" }

    /** What to search for in this scene: its first lyric lines (or label / title). */
    fun sceneText(p: Project, sc: Scene): String {
        val ls = p.lines.filter { it.timed && it.start >= sc.start - 0.01 && it.start < sc.end }.take(3).map { it.text.trim() }
        val t = ls.joinToString(" / ").ifBlank { sc.label.ifBlank { p.title } }
        return t.take(160)
    }

    /** One entry per distinct scene key (a repeated chorus shares one background). Scenes with the user's own media are skipped. */
    class Plan(val key: String, val text: String)

    fun plan(p: Project): List<Plan> {
        val seen = LinkedHashMap<String, Plan>()
        for ((i, sc) in p.scenes.withIndex()) {
            if (!(sc.kind == "gradient" || sc.source == "ai")) continue
            val k = keyOf(sc, i)
            if (!seen.containsKey(k)) seen[k] = Plan(k, sceneText(p, sc))
        }
        return seen.values.toList()
    }

    /** Server returned [items]; plan entry i gets items[i] (cycling if fewer came back). */
    fun pick(items: List<VisualItem>, i: Int): VisualItem = items[i % items.size]

    fun apply(p: Project, results: Map<String, Applied>, texts: Map<String, String>): Int {
        var n = 0
        for ((i, sc) in p.scenes.withIndex()) {
            if (!(sc.kind == "gradient" || sc.source == "ai")) continue
            val k = keyOf(sc, i)
            val r = results[k] ?: continue
            sc.kind = r.kind; sc.path = r.path; sc.source = "ai"; sc.credit = r.credit; sc.query = texts[k] ?: ""
            n++
        }
        return n
    }
}
