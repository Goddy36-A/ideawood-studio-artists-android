package com.ideawood.artists.core

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits a 16 kHz mono 16-bit WAV (what [RenderPlan.extractAudioArgs] produces) into overlapping pieces so each
 * piece can be transcribed by a fresh whisper process, and merges the results back into one transcript.
 * Pure Kotlin/JVM (no Android classes) so it can be unit-tested.
 */
object AudioChunker {
    const val BYTES_PER_SEC = 32000L

    class WavInfo(val dataOffset: Long, val dataLen: Long) {
        val seconds: Double get() = dataLen / BYTES_PER_SEC.toDouble()
    }

    /** [startSec, endSec] is the audio given to whisper (with context); a segment is kept only if its midpoint is in [keepFrom, keepTo). */
    class Piece(val index: Int, val startSec: Double, val endSec: Double, val keepFrom: Double, val keepTo: Double)

    /** Finds the "data" chunk, whatever extra chunks (LIST etc.) the encoder wrote before it. */
    fun readWavInfo(f: File): WavInfo = RandomAccessFile(f, "r").use { raf ->
        val len = raf.length()
        val head = ByteArray(12)
        raf.readFully(head)
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE")
            throw IllegalStateException("Not a WAV file")
        var pos = 12L
        val hdr = ByteArray(8)
        while (pos + 8 <= len) {
            raf.seek(pos)
            raf.readFully(hdr)
            val id = String(hdr, 0, 4, Charsets.US_ASCII)
            var size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "data") {
                val start = pos + 8
                if (size == 0L || start + size > len) size = len - start
                return@use WavInfo(start, size - size % 2)
            }
            pos += 8 + size + (size and 1L)
        }
        throw IllegalStateException("No audio data in WAV")
    }

    /** About [chunkSec] per piece, evenly sized (no tiny last piece), with [overlapSec] of extra context each side. */
    fun plan(totalSec: Double, chunkSec: Double, overlapSec: Double): List<Piece> {
        val n = maxOf(1, Math.round(totalSec / chunkSec).toInt())
        if (n == 1) return listOf(Piece(0, 0.0, totalSec, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))
        val w = totalSec / n
        return (0 until n).map { k ->
            val a = k * w
            val b = (k + 1) * w
            Piece(k, maxOf(0.0, a - overlapSec), minOf(totalSec, b + overlapSec),
                if (k == 0) Double.NEGATIVE_INFINITY else a,
                if (k == n - 1) Double.POSITIVE_INFINITY else b)
        }
    }

    private fun header(dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }

    /** Writes one piece as its own WAV file (streamed, so memory use stays tiny). */
    fun writeChunk(src: File, info: WavInfo, p: Piece, dst: File) {
        val from = (p.startSec * BYTES_PER_SEC).toLong().let { it - it % 2 }
        val to = minOf(info.dataLen, (p.endSec * BYTES_PER_SEC).toLong().let { it - it % 2 })
        val n = maxOf(0L, to - from)
        RandomAccessFile(src, "r").use { inp ->
            FileOutputStream(dst).use { out ->
                out.write(header(n))
                inp.seek(info.dataOffset + from)
                val buf = ByteArray(64 * 1024)
                var left = n
                while (left > 0) {
                    val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) break
                    out.write(buf, 0, r)
                    left -= r
                }
            }
        }
    }

    /** Shifts every piece back to song time and drops the overlap duplicates. */
    fun merge(parts: List<Pair<Piece, Transcript>>): Transcript {
        val out = Transcript()
        for ((p, tr) in parts) {
            if (out.language.isEmpty()) out.language = tr.language
            for (s in tr.segments) {
                val a = s.start + p.startSec
                val b = s.end + p.startSec
                val mid = (a + b) / 2
                if (mid < p.keepFrom || mid >= p.keepTo) continue
                out.segments.add(Segment(a, b, s.text,
                    s.words.map { Word(it.start + p.startSec, it.end + p.startSec, it.text) }.toMutableList()))
            }
        }
        out.segments.sortBy { it.start }
        return out
    }
}
