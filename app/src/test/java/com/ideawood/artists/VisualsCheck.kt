package com.ideawood.artists

import com.ideawood.artists.core.*

/** Checks for the visuals endpoint logic (request body, tolerant response parsing, scene planning). */
object VisualsCheck {
    fun run(): List<String> {
        val f = mutableListOf<String>()
        fun eq(what: String, a: Any?, b: Any?) { if (a != b) f.add("$what: expected $a, got $b") }

        val rb = org.json.JSONObject(VisualsApi.requestBody(listOf("I cry in the rain"), "image"))   // key order is not important
        eq("request", listOf("I cry in the rain", "image"), listOf(rb.getJSONArray("lines").getString(0), rb.getString("media")))
        eq("bad media falls back", true, VisualsApi.requestBody(listOf("x"), "gif").contains("\"image\""))

        val a = VisualsApi.parse("""[{"url":"https://x/a.jpg","credit":"Ann"},{"url":"https://x/b.mp4?sig=1"},{"nope":1},"https://x/c.png"]""")
        eq("array size", 3, a.size)
        eq("array[0]", listOf("https://x/a.jpg", "image", "Ann"), listOf(a[0].url, a[0].kind, a[0].credit))
        eq("array[1] kind from extension", "video", a[1].kind)
        eq("array[2] bare string", "image", a[2].kind)
        val b = VisualsApi.parse("""{"scenes":[{"image_url":"https://x/1.jpg","type":"photo","photographer":{"name":"Bo"}}]}""")
        eq("object wrapper", listOf("https://x/1.jpg", "image", "Bo"), listOf(b[0].url, b[0].kind, b[0].credit))
        eq("empty", 0, VisualsApi.parse("[]").size)
        eq("error text", "bad key", VisualsApi.errorText("""{"error":"bad key"}"""))
        eq("error text html", "", VisualsApi.errorText("<html>oops</html>"))

        val lines = listOf(
            LyricLine("one a", 1.0, 3.0, stanza = 0), LyricLine("one b", 3.0, 5.0, stanza = 0),
            LyricLine("two a", 12.0, 14.0, stanza = 1), LyricLine("one a", 22.0, 24.0, stanza = 2),
        )
        val p = Project().also { it.lines = lines.toMutableList(); it.title = "T" }
        p.scenes = mutableListOf(
            Scene(0.0, 10.0, key = "k1"), Scene(10.0, 20.0, key = "k2", kind = "image", path = "/mine.jpg", source = "mine"),
            Scene(20.0, 30.0, key = "k1"),
        )
        val plan = VisualsApi.plan(p)
        eq("plan keys (chorus shared, own media skipped)", listOf("k1"), plan.map { it.key })
        eq("plan text", "one a / one b", plan[0].text)
        val n = VisualsApi.apply(p, mapOf("k1" to Applied("/ai.jpg", "image", "Ann")), mapOf("k1" to "one a / one b"))
        eq("apply count", 2, n)
        eq("apply sets both chorus scenes", listOf("/ai.jpg", "/mine.jpg", "/ai.jpg"), p.scenes.map { it.path })
        eq("own media untouched", "mine", p.scenes[1].source)
        eq("pick cycles", "https://x/a.jpg", VisualsApi.pick(a, 3).url)
        return f
    }
}
