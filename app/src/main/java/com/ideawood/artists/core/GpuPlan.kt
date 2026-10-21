package com.ideawood.artists.core

/**
 * Pure timeline / animation maths for the GPU background renderer (kept free of Android classes so it is unit-tested on the JVM).
 * Mirrors what RenderPlan builds for ffmpeg: scene k fades IN over its first [transition] seconds while scene k-1 keeps playing
 * (extended) underneath, and every picture gets one of four Ken Burns moves chosen by scene index.
 */
object GpuPlan {
    class Slot(val start: Double, val end: Double, val src: Scene) {
        val duration: Double get() = end - start
    }

    class Layers(val a: Int, val aT: Double, val b: Int, val bT: Double, val mix: Double)

    class Timeline(val slots: List<Slot>, val transition: Double) {
        /** Which scene(s) are on screen at output time [t]. b = -1 and mix = -1 when there is no transition running. */
        fun frameAt(t: Double): Layers {
            var k = 0
            for (i in slots.indices) if (slots[i].start <= t + 1e-9) k = i
            val s = slots[k]
            if (k >= 1 && transition > 0 && t < s.start + transition) {
                val p = ((t - s.start) / transition).coerceIn(0.0, 1.0)
                return Layers(k - 1, t - slots[k - 1].start, k, t - s.start, p)
            }
            return Layers(k, t - s.start, -1, 0.0, -1.0)
        }

        /** Length used for the Ken Burns progress of scene [i] (the scene plus the overlap that follows it). */
        fun lenOf(i: Int): Double = slots[i].duration + (if (i < slots.size - 1) transition else 0.0)
    }

    fun timeline(p: Project): Timeline {
        val copies = p.scenes.filter { it.duration > 0.05 }.map {
            Scene(it.start, it.end, it.kind, it.path, it.query, it.source, it.credit, it.key, it.colors.toMutableList(), it.label)
        }.toMutableList()
        if (copies.isEmpty()) copies.add(Scene(0.0, p.duration))
        copies[0].start = 0.0
        copies.last().end = maxOf(copies.last().end, p.duration)
        val tr = RenderPlan.effectiveTransition(p.style, copies)
        return Timeline(copies.map { Slot(it.start, it.end, it) }, tr)
    }

    /** Visible window (x0, y0, w, h as fractions of the cover-cropped picture) for scene index [i] at progress [p] (0..1). */
    fun kenBurns(i: Int, p: Double, enabled: Boolean): DoubleArray {
        if (!enabled) return doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        val q = p.coerceIn(0.0, 1.0)
        val (z, px) = when (i % 4) {
            0 -> (1.0 + 0.10 * q) to 0.5
            1 -> (1.10 - 0.10 * q) to 0.5
            2 -> 1.08 to q
            else -> 1.08 to (1.0 - q)
        }
        val w = 1.0 / z
        return doubleArrayOf((1.0 - w) * px, (1.0 - w) * 0.5, w, w)
    }

    /** Part of the picture (u0, v0, du, dv in 0..1, v from the top) that fills a frame of the given aspect without stretching. */
    fun coverRect(imgW: Int, imgH: Int, frameW: Int, frameH: Int): DoubleArray {
        val ta = imgW.toDouble() / imgH
        val fa = frameW.toDouble() / frameH
        return if (ta > fa) {
            val du = fa / ta
            doubleArrayOf((1.0 - du) / 2, 0.0, du, 1.0)
        } else {
            val dv = ta / fa
            doubleArrayOf(0.0, (1.0 - dv) / 2, 1.0, dv)
        }
    }

    /** Cover crop first, then the Ken Burns window inside it -> final texture rectangle. */
    fun finalRect(cover: DoubleArray, kb: DoubleArray): DoubleArray = doubleArrayOf(
        cover[0] + kb[0] * cover[2], cover[1] + kb[1] * cover[3], cover[2] * kb[2], cover[3] * kb[3],
    )
}
