package com.ideawood.artists

import com.ideawood.artists.core.AudioChunker
import com.ideawood.artists.core.Segment
import com.ideawood.artists.core.Transcript
import com.ideawood.artists.core.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioChunkerTest {
    /** A WAV like ffmpeg writes it: with an extra LIST chunk before the data. */
    private fun wav(seconds: Int): File {
        val n = seconds * 32000
        val b = ByteBuffer.allocate(12 + 24 + 22 + 8 + n).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(b.capacity() - 8).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        b.put("LIST".toByteArray()).putInt(14).put(ByteArray(14))
        b.put("data".toByteArray()).putInt(n)
        val f = File.createTempFile("chunk", ".wav"); f.deleteOnExit(); f.writeBytes(b.array()); return f
    }

    @Test fun findsDataAfterExtraChunks() {
        val info = AudioChunker.readWavInfo(wav(10))
        assertEquals(66L, info.dataOffset)
        assertEquals(320000L, info.dataLen)
        assertEquals(10.0, info.seconds, 1e-9)
    }

    @Test fun planCoversSongWithoutGaps() {
        val ps = AudioChunker.plan(250.0, 90.0, 3.0)
        assertEquals(3, ps.size)
        for (i in 1 until ps.size) assertEquals(ps[i - 1].keepTo, ps[i].keepFrom, 1e-9)
        assertEquals(0.0, ps.first().startSec, 1e-9)
        assertEquals(250.0, ps.last().endSec, 1e-9)
        assertEquals(1, AudioChunker.plan(120.0, 90.0, 3.0).size)   // short song = one piece, same as before
    }

    @Test fun writesPlayableChunk() {
        val src = wav(100)
        val info = AudioChunker.readWavInfo(src)
        val p = AudioChunker.plan(100.0, 40.0, 3.0)[1]
        val dst = File.createTempFile("piece", ".wav"); dst.deleteOnExit()
        AudioChunker.writeChunk(src, info, p, dst)
        val got = AudioChunker.readWavInfo(dst)
        assertEquals(44L, got.dataOffset)
        assertEquals((p.endSec - p.startSec), got.seconds, 0.001)
    }

    @Test fun mergeShiftsTimesAndDropsOverlapDuplicates() {
        val ps = AudioChunker.plan(180.0, 90.0, 3.0)         // two pieces, cut at 90 s
        fun seg(a: Double, b: Double, t: String) = Segment(a, b, t, mutableListOf(Word(a, b, t)))
        val first = Transcript("en", mutableListOf(seg(10.0, 12.0, "a"), seg(88.0, 91.0, "edge")))    // piece 0 starts at 0
        val second = Transcript("en", mutableListOf(seg(0.5, 2.0, "dup-in-overlap"), seg(5.0, 7.0, "b")))  // piece 1 starts at 87
        val m = AudioChunker.merge(listOf(ps[0] to first, ps[1] to second))
        assertEquals(listOf("a", "edge", "b"), m.segments.map { it.text })
        assertTrue(m.segments[2].start in 91.9..92.1)       // 5 s into a piece that starts at 87 s
        assertEquals(92.0, m.segments[2].words[0].start, 1e-9)
    }
}
