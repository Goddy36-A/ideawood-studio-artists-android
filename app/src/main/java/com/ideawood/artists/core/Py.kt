package com.ideawood.artists.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.regex.Pattern

/** Small helpers that reproduce Python behaviour exactly, so Kotlin output matches the desktop app. */
object Py {
    // Android's regex engine rejects Pattern.UNICODE_CHARACTER_CLASS (IllegalArgumentException), which crashed this object's init.
    // There \s is already Unicode-aware, so fall back to the plain pattern. The desktop JVM (tests) keeps the flag for exact Python parity.
    private val WS: Pattern = try { Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS) } catch (e: IllegalArgumentException) { Pattern.compile("\\s+") }

    /** Python round(x) (banker's rounding) -> Int. */
    fun round(x: Double): Int = Math.rint(x).toInt()

    /** Python f"{v:.{d}f}" (round-half-even on the exact binary value, keeps "-0"). */
    fun fmt(v: Double, d: Int): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        val s = BigDecimal(v).setScale(d, RoundingMode.HALF_EVEN).toPlainString()
        val negative = v < 0 || (v == 0.0 && 1.0 / v < 0)
        return if (negative && !s.startsWith("-")) "-$s" else s
    }

    /** Python str.split() with no arguments. */
    fun split(s: String): List<String> {
        val t = s.trim()
        return if (t.isEmpty()) emptyList() else WS.split(t).toList()
    }

    /** Python str.title(). */
    fun title(s: String): String {
        val sb = StringBuilder()
        var prevLetter = false
        for (ch in s) {
            if (ch.isLetter()) {
                sb.append(if (prevLetter) ch.lowercaseChar() else ch.uppercaseChar())
                prevLetter = true
            } else {
                sb.append(ch)
                prevLetter = false
            }
        }
        return sb.toString()
    }

    fun hex2(v: Int): String = String.format(java.util.Locale.ROOT, "%02X", v)
}

/** Port of Python's difflib.SequenceMatcher (autojunk=False, no junk), generic over the element type. */
class SeqMatcher<T>(private val a: List<T>, private val b: List<T>) {
    data class Match(val a: Int, val b: Int, val size: Int)

    private val b2j = HashMap<T, MutableList<Int>>()

    init {
        b.forEachIndexed { j, e -> b2j.getOrPut(e) { mutableListOf() }.add(j) }
    }

    private fun longest(alo: Int, ahi: Int, blo: Int, bhi: Int): Match {
        var besti = alo
        var bestj = blo
        var bestsize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            val js = b2j[a[i]]
            if (js != null) {
                for (j in js) {
                    if (j < blo) continue
                    if (j >= bhi) break
                    val k = (j2len[j - 1] ?: 0) + 1
                    newj2len[j] = k
                    if (k > bestsize) {
                        besti = i - k + 1; bestj = j - k + 1; bestsize = k
                    }
                }
            }
            j2len = newj2len
        }
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
            besti--; bestj--; bestsize++
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize]) {
            bestsize++
        }
        return Match(besti, bestj, bestsize)
    }

    /** Same list as Python's get_matching_blocks(), including the final (len(a), len(b), 0) sentinel. */
    fun matchingBlocks(): List<Match> {
        val la = a.size
        val lb = b.size
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(0, la, 0, lb))
        val found = ArrayList<Match>()
        while (queue.isNotEmpty()) {
            val q = queue.removeLast()
            val alo = q[0]; val ahi = q[1]; val blo = q[2]; val bhi = q[3]
            val m = longest(alo, ahi, blo, bhi)
            if (m.size > 0) {
                found.add(m)
                if (alo < m.a && blo < m.b) queue.addLast(intArrayOf(alo, m.a, blo, m.b))
                if (m.a + m.size < ahi && m.b + m.size < bhi) {
                    queue.addLast(intArrayOf(m.a + m.size, ahi, m.b + m.size, bhi))
                }
            }
        }
        found.sortWith(compareBy<Match>({ it.a }, { it.b }, { it.size }))
        var i1 = 0
        var j1 = 0
        var k1 = 0
        val out = ArrayList<Match>()
        for (m in found) {
            if (i1 + k1 == m.a && j1 + k1 == m.b) {
                k1 += m.size
            } else {
                if (k1 > 0) out.add(Match(i1, j1, k1))
                i1 = m.a; j1 = m.b; k1 = m.size
            }
        }
        if (k1 > 0) out.add(Match(i1, j1, k1))
        out.add(Match(la, lb, 0))
        return out
    }

    fun ratio(): Double {
        val total = a.size + b.size
        if (total == 0) return 1.0
        val matches = matchingBlocks().sumOf { it.size }
        return 2.0 * matches / total
    }
}
