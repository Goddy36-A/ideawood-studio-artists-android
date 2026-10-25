package com.ideawood.artists

import com.ideawood.artists.core.Asr
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqParseTest {
    private val sample = """
        {"text":" hello world again","language":"English","duration":9.0,
         "segments":[{"id":0,"start":0.0,"end":3.0,"text":" Hello world,"},
                     {"id":1,"start":3.0,"end":6.0,"text":" again"},
                     {"id":2,"start":6.0,"end":9.0,"text":" [Music]"}],
         "words":[{"word":"Hello","start":0.2,"end":0.8},{"word":"world,","start":1.0,"end":1.6},
                  {"word":"again","start":3.4,"end":4.1}]}
    """.trimIndent()

    @Test fun mapsLanguageNameToCodeAndHidesNonSpeech() {
        val tr = Asr.parseGroqJson(JSONObject(sample))
        assertEquals("en", tr.language)
        assertEquals(listOf("Hello world,", "again"), tr.segments.map { it.text })
    }

    @Test fun assignsWordsToTheirSegments() {
        val tr = Asr.parseGroqJson(JSONObject(sample))
        assertEquals(listOf("Hello", "world,"), tr.segments[0].words.map { it.text })
        assertEquals(listOf("again"), tr.segments[1].words.map { it.text })
        assertEquals(0.2, tr.segments[0].words[0].start, 1e-9)
        assertTrue(tr.segments[1].words[0].end <= tr.segments[1].end + 1e-9)
    }

    @Test fun missingWordsStillGivesSegments() {
        val tr = Asr.parseGroqJson(JSONObject("""{"language":"sw","segments":[{"start":0.0,"end":2.0,"text":" Habari"}]}"""))
        assertEquals("sw", tr.language)
        assertEquals(1, tr.segments.size)
        assertTrue(tr.segments[0].words.isEmpty())
    }
}
