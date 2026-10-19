package com.ideawood.artists.core

import java.io.File

/**
 * Port of ideawood_artists/render.py. Builds the exact same single-pass ffmpeg filter graph as the desktop app
 * (scenes -> crossfades -> dim/blur/grade/vignette/grain -> visualiser/logo -> libass lyrics), as an argument list
 * for FFmpegKit. Only the encoder differs: phones use the hardware H.264 encoder by default.
 */
object RenderPlan {
    const val FPS = 30
    const val KB_SCALE = 1.5

    enum class Encoder { MEDIACODEC, X264, MPEG4 }

    class Built(val args: List<String>, val duration: Double)

    private val SAFE = Regex("^[A-Za-z0-9_./-]+$")

    /** Paths used inside the filter graph must not contain characters ffmpeg treats specially. */
    fun requireSafe(path: String): String {
        require(SAFE.matches(path)) { "Unsupported characters in internal path: $path" }
        return path
    }

    private fun even(x: Double): Int = x.toInt() / 2 * 2
    private fun hex(c: String?): String = "0x" + (if (c.isNullOrEmpty()) "#000000" else c).trimStart('#').take(6)

    private class Segment(val args: List<String>, val filter: String)

    private fun segment(idx: Int, i: Int, sc: Scene, len: Double, w: Int, h: Int, st: LyricStyle, seed: Int): Segment {
        val end = "setsar=1,format=yuv420p,fps=$FPS,settb=1/$FPS,setpts=PTS-STARTPTS[s$i]"
        val usable = (sc.kind == "image" || sc.kind == "video") && sc.path.isNotEmpty() && File(sc.path).isFile
        val l3 = Py.fmt(len, 3)
        if (usable && sc.kind == "video") {
            return Segment(listOf("-stream_loop", "-1", "-t", l3, "-i", sc.path),
                "[$idx:v]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,$end")
        }
        if (usable && sc.kind == "image") {
            if (st.kenBurns) {
                val n = maxOf(2, (len * FPS).toInt() + 2)
                val bw = even(w * KB_SCALE)
                val bh = even(h * KB_SCALE)
                val v = i % 4
                val cx = "iw/2-(iw/zoom/2)"
                val cy = "ih/2-(ih/zoom/2)"
                val (z, x, y) = when (v) {
                    0 -> Triple("1+0.10*on/$n", cx, cy)
                    1 -> Triple("1.10-0.10*on/$n", cx, cy)
                    2 -> Triple("1.08", "(iw-iw/zoom)*on/$n", cy)
                    else -> Triple("1.08", "(iw-iw/zoom)*(1-on/$n)", cy)
                }
                return Segment(listOf("-i", sc.path),
                    "[$idx:v]scale=$bw:$bh:force_original_aspect_ratio=increase,crop=$bw:$bh," +
                        "zoompan=z='$z':x='$x':y='$y':d=$n:s=${w}x$h:fps=$FPS,trim=duration=$l3,$end")
            }
            return Segment(listOf("-loop", "1", "-framerate", "$FPS", "-t", l3, "-i", sc.path),
                "[$idx:v]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,$end")
        }
        val cols = (sc.colors + listOf("#141E30", "#243B55")).take(2)
        return Segment(listOf("-f", "lavfi", "-i",
            "gradients=s=${w}x$h:r=$FPS:d=$l3:c0=${hex(cols[0])}:c1=${hex(cols[1])}:nb_colors=2:speed=0.012:" +
                "seed=${(seed * 7 + i * 13) % 1000 + 1}"), "[$idx:v]$end")
    }

    fun postChain(st: LyricStyle, h: Int): String {
        val parts = mutableListOf<String>()
        if (st.bgBlur > 0) parts.add("gblur=sigma=${Py.fmt(st.bgBlur * h / 1080, 1)}")
        val g = GRADES[st.grade] ?: ""
        if (g.isNotEmpty()) parts.add(g)
        if (st.bgDim > 0) parts.add("drawbox=x=0:y=0:w=iw:h=ih:color=black@${Py.fmt(minOf(st.bgDim, 0.9), 2)}:t=fill")
        if (st.vignette) parts.add("vignette=angle=PI/5")
        if (st.grain > 0) parts.add("noise=alls=${(st.grain * 150).toInt()}:allf=t")
        return if (parts.isEmpty()) "null" else parts.joinToString(",")
    }

    private fun logoPos(name: String): String {
        val m = "W*0.03"
        return when (name) {
            "Top left" -> "$m:$m"
            "Top right" -> "W-w-$m:$m"
            "Bottom left" -> "$m:H-h-$m"
            "Bottom right" -> "W-w-$m:H-h-$m"
            else -> "W-w-$m:$m"
        }
    }

    fun effectiveTransition(st: LyricStyle, scenes: List<Scene>): Double {
        if (st.transition == "none" || scenes.size < 2) return 0.0
        return maxOf(0.2, minOf(st.transitionSec, scenes.minOf { it.duration } * 0.45))
    }

    private fun encoderArgs(enc: Encoder, preview: Boolean, short: Int): List<String> = when (enc) {
        Encoder.X264 -> listOf("-c:v", "libx264", "-preset", if (preview) "ultrafast" else "medium",
            "-crf", "${if (preview) 27 else (CRF[short] ?: 20)}", "-pix_fmt", "yuv420p", "-profile:v", "high")
        Encoder.MEDIACODEC -> listOf("-c:v", "h264_mediacodec", "-b:v",
            "${if (preview) 2_000_000 else (MEDIACODEC_BITRATE[short] ?: 12_000_000)}", "-g", "${FPS * 2}", "-pix_fmt", "yuv420p")
        Encoder.MPEG4 -> listOf("-c:v", "mpeg4", "-q:v", if (preview) "6" else "3", "-pix_fmt", "yuv420p")
    }

    /**
     * @param assPath absolute path of the generated lyrics.ass (must pass [requireSafe])
     * @param fontsDir absolute folder with the .ttf/.otf files libass should use
     */
    fun buildCommand(p: Project, outPath: String, assPath: String, fontsDir: String, encoder: Encoder = Encoder.MEDIACODEC,
                     preview: Boolean = false, t0: Double = 0.0, span: Double? = null): Built {
        val short = if (preview) 480 else (QUALITIES[p.quality] ?: 1080)
        val (w, h) = frameSize(p.fmt, short)
        val st = p.style
        var scenes = p.scenes.filter { it.duration > 0.05 }.toMutableList()
        if (scenes.isEmpty()) scenes = mutableListOf(Scene(0.0, p.duration))
        scenes[0].start = 0.0
        scenes.last().end = maxOf(scenes.last().end, p.duration)
        val tr = effectiveTransition(st, scenes)
        val n = scenes.size

        val inputs = mutableListOf<String>()
        val filt = mutableListOf<String>()
        for ((i, sc) in scenes.withIndex()) {
            val len = sc.duration + (if (i < n - 1) tr else 0.0)
            val seg = segment(i, i, sc, len, w, h, st, (p.duration).toInt() + 1)
            inputs += seg.args
            filt.add(seg.filter)
        }
        if (n == 1) {
            filt.add("[s0]null[vj]")
        } else if (tr <= 0) {
            filt.add((0 until n).joinToString("") { "[s$it]" } + "concat=n=$n:v=1:a=0[vj]")
        } else {
            var cur = "[s0]"
            var off = 0.0
            for (k in 1 until n) {
                off += scenes[k - 1].duration
                val out = if (k == n - 1) "[vj]" else "[x$k]"
                filt.add("$cur[s$k]xfade=transition=${st.transition}:duration=${Py.fmt(tr, 3)}:offset=${Py.fmt(off, 3)}$out")
                cur = out
            }
        }
        filt.add("[vj]${postChain(st, h)}[vp]")
        var cur = "[vp]"

        val aIdx = inputs.count { it == "-i" }
        inputs += listOf("-i", p.audio)
        val audioLabel = "$aIdx:a"
        val useLogo = p.logo.isNotEmpty() && File(p.logo).isFile
        val aSrc: String
        if (st.viz != "none") {
            val vh = even(h * 0.16)
            filt.add("[$audioLabel]asplit=2[aout][aviz]")
            val col = st.vizColor
            if (st.viz == "bars") {
                filt.add("[aviz]showfreqs=s=${w}x$vh:mode=bar:ascale=sqrt:fscale=log:win_size=2048:" +
                    "colors=${hex(col)}:rate=$FPS,format=rgba,colorkey=black:0.15:0.2[viz]")
            } else {
                filt.add("[aviz]showwaves=s=${w}x$vh:mode=cline:rate=$FPS:colors=${hex(col)}:scale=sqrt," +
                    "format=rgba,colorkey=black:0.15:0.2[viz]")
            }
            val y = if (st.posY > 0.68) "H*0.04" else "H-h-H*0.04"
            filt.add("$cur[viz]overlay=0:$y:format=auto:shortest=0[vv]")
            cur = "[vv]"; aSrc = "[aout]"
        } else aSrc = "[$audioLabel]"
        if (useLogo) {
            val li = inputs.count { it == "-i" }
            inputs += listOf("-i", p.logo)
            filt.add("[$li:v]scale=${even(w * p.logoSize)}:-2,format=rgba,colorchannelmixer=aa=0.9[lg]")
            filt.add("$cur[lg]overlay=${logoPos(p.logoPos)}:format=auto[vl]")
            cur = "[vl]"
        }
        filt.add("${cur}ass=filename=${requireSafe(assPath)}:fontsdir=${requireSafe(fontsDir)},format=yuv420p[v]")
        if (p.normalizeAudio) filt.add("$aSrc" + "loudnorm=I=-14:TP=-1.5:LRA=11,aresample=48000[a]")
        else filt.add("$aSrc" + "aresample=48000[a]")

        val total = p.duration
        val outDur = if (span == null) total else maxOf(0.5, minOf(span, total - t0))
        val cmd = mutableListOf("-y", "-nostdin")
        cmd += inputs
        cmd += listOf("-filter_complex", filt.joinToString(";"), "-map", "[v]", "-map", "[a]")
        if (preview) cmd += listOf("-ss", Py.fmt(t0, 2))
        cmd += listOf("-t", Py.fmt(outDur, 2), "-r", "$FPS")
        cmd += encoderArgs(encoder, preview, short)
        cmd += listOf("-c:a", "aac", "-b:a", "256k", "-ar", "48000", "-movflags", "+faststart", outPath)
        return Built(cmd, outDur)
    }

    /** One still frame at time [t] (background of that scene + lyrics) for the live look preview. */
    fun buildPreviewFrame(p: Project, t: Double, outPng: String, assPath: String, fontsDir: String): List<String> {
        val sc = p.scenes.firstOrNull { it.start <= t && t < it.end } ?: p.scenes.firstOrNull() ?: Scene(0.0, maxOf(p.duration, 1.0))
        val idx = maxOf(0, p.scenes.indexOf(sc))
        val sc1 = Scene(0.0, 2.0, sc.kind, sc.path, colors = sc.colors)
        val st = p.style.copy(kenBurns = false)
        val (w, h) = frameSize(p.fmt, 540)
        val seg = segment(0, idx, sc1, 2.0, w, h, st, (p.duration).toInt() + 1)
        val fc = "${seg.filter};[s$idx]${postChain(st, h)},setpts=PTS+${Py.fmt(t, 3)}/TB," +
            "ass=filename=${requireSafe(assPath)}:fontsdir=${requireSafe(fontsDir)},format=rgb24[v]"
        return listOf("-y", "-nostdin", "-v", "error") + seg.args + listOf("-filter_complex", fc, "-map", "[v]", "-frames:v", "1", outPng)
    }

    /** Any audio/video -> 16 kHz mono WAV (what whisper wants). */
    fun extractAudioArgs(src: String, dstWav: String) =
        listOf("-y", "-nostdin", "-v", "error", "-i", src, "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", dstWav)
}
