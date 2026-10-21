package com.ideawood.artists

import com.ideawood.artists.core.*
import org.junit.Assert.*
import org.junit.Test

class GpuPlanTest {
    private fun proj(tr: String = "fade", sec: Double = 1.0): Project {
        val p = Project()
        p.duration = 30.0
        p.scenes = mutableListOf(Scene(0.0, 10.0), Scene(10.0, 20.0), Scene(20.0, 30.0))
        p.style = p.style.copy(transition = tr, transitionSec = sec)
        return p
    }

    @Test fun noTransitionInsideAScene() {
        val tl = GpuPlan.timeline(proj())
        val f = tl.frameAt(15.0)
        assertEquals(1, f.a); assertEquals(5.0, f.aT, 1e-9); assertEquals(-1, f.b)
    }

    @Test fun newSceneFadesInOverItsFirstSecond() {
        val tl = GpuPlan.timeline(proj())
        val f = tl.frameAt(10.25)
        assertEquals(0, f.a); assertEquals(10.25, f.aT, 1e-9)   // previous scene keeps playing past its end
        assertEquals(1, f.b); assertEquals(0.25, f.bT, 1e-9)
        assertEquals(0.25, f.mix, 1e-9)
        assertEquals(-1, tl.frameAt(11.0).b)
    }

    @Test fun noneMeansHardCuts() {
        val tl = GpuPlan.timeline(proj("none"))
        assertEquals(0.0, tl.transition, 0.0)
        assertEquals(-1, tl.frameAt(10.1).b)
        assertEquals(1, tl.frameAt(10.1).a)
    }

    @Test fun kenBurnsVariants() {
        val a = GpuPlan.kenBurns(0, 0.0, true); assertEquals(1.0, a[2], 1e-9)
        val b = GpuPlan.kenBurns(0, 1.0, true); assertEquals(1.0 / 1.1, b[2], 1e-9); assertEquals((1 - 1 / 1.1) / 2, b[0], 1e-9)
        val c = GpuPlan.kenBurns(2, 0.0, true); assertEquals(0.0, c[0], 1e-9)
        val d = GpuPlan.kenBurns(2, 1.0, true); assertEquals(1.0 - 1.0 / 1.08, d[0], 1e-9)
        val off = GpuPlan.kenBurns(1, 0.5, false); assertArrayEquals(doubleArrayOf(0.0, 0.0, 1.0, 1.0), off, 0.0)
    }

    @Test fun coverCropKeepsAspect() {
        val wide = GpuPlan.coverRect(4000, 1000, 1280, 720)       // very wide photo -> crop left/right
        assertEquals(1.0, wide[3], 1e-9); assertTrue(wide[2] < 1.0)
        val tall = GpuPlan.coverRect(1000, 4000, 1280, 720)       // tall photo -> crop top/bottom
        assertEquals(1.0, tall[2], 1e-9); assertTrue(tall[3] < 1.0)
        val r = GpuPlan.finalRect(wide, GpuPlan.kenBurns(0, 1.0, true))
        assertTrue(r[0] >= 0 && r[0] + r[2] <= 1.0 + 1e-9 && r[1] >= 0 && r[1] + r[3] <= 1.0 + 1e-9)
    }
}
