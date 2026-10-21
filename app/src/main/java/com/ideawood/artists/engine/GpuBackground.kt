package com.ideawood.artists.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import com.ideawood.artists.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * Draws the BACKGROUND of the lyric video (pictures, Ken Burns, crossfades, grade, dim, vignette, grain) on the phone's GPU
 * with OpenGL ES, and encodes it with the hardware H.264 encoder straight into an mp4 (no sound, no lyrics).
 * The lyrics, logo and audio are added afterwards by a light ffmpeg pass (see Pipeline), so the lyric look stays exactly as before.
 */
class GpuBackground {
    companion object {
        /** Video backgrounds and the audio visualiser are not drawn here yet - those projects use the standard renderer. */
        fun supports(p: Project): Boolean {
            if (p.style.viz != "none") return false
            return p.scenes.none { it.kind == "video" && it.path.isNotEmpty() && File(it.path).isFile }
        }

        private val TRANS_IDS = mapOf("fade" to 0, "dissolve" to 1, "fadeblack" to 2, "smoothleft" to 3,
            "slideleft" to 4, "wipeleft" to 5, "circleopen" to 6, "radial" to 7)

        private const val VERT = """
attribute vec2 aPos;
varying vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

        private const val FRAG = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uTexA;
uniform sampler2D uTexB;
uniform vec4 uRectA;
uniform vec4 uRectB;
uniform vec3 uA0;
uniform vec3 uA1;
uniform vec3 uB0;
uniform vec3 uB1;
uniform float uKindA;
uniform float uKindB;
uniform float uAngA;
uniform float uAngB;
uniform float uMix;
uniform float uTrans;
uniform vec2 uRes;
uniform float uSeed;
uniform float uBlur;
uniform float uDim;
uniform float uVig;
uniform float uGrain;
uniform vec3 uShadow;
uniform vec3 uMid;
uniform vec3 uHigh;
uniform float uCon;
uniform float uSat;
uniform float uLift;

float rnd(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

vec3 grad(vec2 uv, vec3 c0, vec3 c1, float ang) {
  vec2 d = vec2(cos(ang), sin(ang));
  float g = dot(uv - 0.5, d) + 0.5;
  return mix(c0, c1, smoothstep(0.0, 1.0, g));
}
vec3 rawA(vec2 uv) {
  if (uKindA < 0.5) return grad(uv, uA0, uA1, uAngA);
  vec2 t = vec2(uRectA.x + uv.x * uRectA.z, uRectA.y + (1.0 - uv.y) * uRectA.w);
  return texture2D(uTexA, t).rgb;
}
vec3 rawB(vec2 uv) {
  if (uKindB < 0.5) return grad(uv, uB0, uB1, uAngB);
  vec2 t = vec2(uRectB.x + uv.x * uRectB.z, uRectB.y + (1.0 - uv.y) * uRectB.w);
  return texture2D(uTexB, t).rgb;
}
vec3 fetchA(vec2 uv) {
  if (uBlur <= 0.0) return rawA(uv);
  vec2 o = vec2(uBlur) / uRes;
  vec3 s = rawA(uv) * 0.2;
  s += (rawA(uv + vec2(o.x, 0.0)) + rawA(uv - vec2(o.x, 0.0)) + rawA(uv + vec2(0.0, o.y)) + rawA(uv - vec2(0.0, o.y))) * 0.15;
  s += (rawA(uv + o) + rawA(uv - o) + rawA(uv + vec2(o.x, -o.y)) + rawA(uv + vec2(-o.x, o.y))) * 0.05;
  return s;
}
vec3 fetchB(vec2 uv) {
  if (uBlur <= 0.0) return rawB(uv);
  vec2 o = vec2(uBlur) / uRes;
  vec3 s = rawB(uv) * 0.2;
  s += (rawB(uv + vec2(o.x, 0.0)) + rawB(uv - vec2(o.x, 0.0)) + rawB(uv + vec2(0.0, o.y)) + rawB(uv - vec2(0.0, o.y))) * 0.15;
  s += (rawB(uv + o) + rawB(uv - o) + rawB(uv + vec2(o.x, -o.y)) + rawB(uv + vec2(-o.x, o.y))) * 0.05;
  return s;
}

void main() {
  vec2 uv = vUv;
  float asp = uRes.x / uRes.y;
  vec3 c;
  if (uMix < 0.0) {
    c = fetchA(uv);
  } else {
    float p = uMix;
    int id = int(uTrans + 0.5);
    if (id == 4) {
      if (uv.x < 1.0 - p) c = fetchA(vec2(uv.x + p, uv.y));
      else c = fetchB(vec2(uv.x - (1.0 - p), uv.y));
    } else {
      vec3 a = fetchA(uv);
      vec3 b = fetchB(uv);
      if (id == 2) {
        c = (p < 0.5) ? a * (1.0 - 2.0 * p) : b * (2.0 * p - 1.0);
      } else {
        float m = p;
        if (id == 1) m = step(rnd(uv * uRes), p);
        else if (id == 3) { float e = (1.0 - p) * 1.3 - 0.15; m = smoothstep(e - 0.1, e + 0.1, uv.x); }
        else if (id == 5) m = step(1.0 - p, uv.x);
        else if (id == 6) {
          vec2 q = (uv - 0.5) * vec2(asp, 1.0);
          float maxd = 0.5 * length(vec2(asp, 1.0));
          m = step(length(q), p * maxd * 1.02);
        } else if (id == 7) {
          float an = atan(uv.x - 0.5, uv.y - 0.5) / 6.2831853 + 0.5;
          m = step(an, p);
        }
        c = mix(a, b, m);
      }
    }
  }
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c += uShadow * (1.0 - l) * (1.0 - l) + uHigh * l * l + uMid * (4.0 * l * (1.0 - l));
  c = (c - 0.5) * uCon + 0.5 + uLift;
  float l2 = dot(c, vec3(0.299, 0.587, 0.114));
  c = mix(vec3(l2), c, uSat);
  c *= (1.0 - uDim);
  if (uVig > 0.5) {
    vec2 q = (uv - 0.5) * vec2(asp, 1.0);
    float d = length(q) / (0.5 * length(vec2(asp, 1.0)));
    float v = cos(d * 0.6283185);
    c *= pow(max(v, 0.0), 4.0);
  }
  if (uGrain > 0.0) c += (rnd(uv * uRes + uSeed) - 0.5) * uGrain;
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

        private class Grade(val shadow: FloatArray, val mid: FloatArray, val high: FloatArray, val con: Float, val sat: Float, val lift: Float)

        private val Z = floatArrayOf(0f, 0f, 0f)
        /** Close approximations of the ffmpeg colour grades in core/Models.kt (GRADES). */
        private val GRADE_TABLE = mapOf(
            "None" to Grade(Z, Z, Z, 1f, 1f, 0f),
            "Cinematic" to Grade(floatArrayOf(-0.06f, 0f, 0.07f), Z, floatArrayOf(0.07f, 0f, -0.07f), 1.08f, 1.12f, 0f),
            "Warm film" to Grade(floatArrayOf(0.07f, 0f, -0.07f), floatArrayOf(0.05f, 0f, -0.05f), Z, 1f, 1.1f, 0f),
            "Cool night" to Grade(floatArrayOf(-0.06f, 0f, 0.09f), floatArrayOf(-0.04f, 0f, 0.06f), Z, 1.05f, 1f, 0f),
            "Vibrant" to Grade(Z, Z, Z, 1.08f, 1.4f, 0f),
            "Faded vintage" to Grade(Z, floatArrayOf(0.03f, 0f, -0.03f), Z, 0.9f, 0.8f, 0.05f),
            "Black & white" to Grade(Z, Z, Z, 1.12f, 0f, 0f),
        )
    }

    private class Tex(val id: Int, val w: Int, val h: Int)

    private fun rgb(hex: String, def: String): FloatArray {
        val c = try { Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") } catch (e: Exception) { Color.parseColor(def) }
        return floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw IllegalStateException("Shader error: $log") }
        return s
    }

    private fun loadTexture(path: String, fw: Int, fh: Int): Tex? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= fw * 1.25 && bounds.outHeight / (sample * 2) >= fh * 1.25) sample *= 2
        var bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val rot = try {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (rot != 0f) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        val t = Tex(ids[0], bmp.width, bmp.height)
        bmp.recycle()
        return t
    }

    /**
     * Renders [outDur] seconds starting at timeline position [t0] into [out] (video only).
     * Must be called from ONE thread without suspending (it owns the EGL context).
     */
    fun render(p: Project, out: File, preview: Boolean, t0: Double, outDur: Double,
               onProgress: (Double) -> Unit, cancelled: () -> Boolean) {
        val short = if (preview) 480 else (QUALITIES[p.quality] ?: 720)
        val (w, h) = frameSize(p.fmt, short)
        val fps = RenderPlan.FPS
        val tl = GpuPlan.timeline(p)
        val st = p.style
        val bitrate = ((if (preview) 2_000_000 else (MEDIACODEC_BITRATE[short] ?: 12_000_000)) * 1.5).toInt()
        val frames = maxOf(1, Math.ceil(outDur * fps).toInt())

        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        var surf: Surface? = null
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        var esf: EGLSurface = EGL14.EGL_NO_SURFACE
        var prog = 0
        val cache = HashMap<Int, Tex>()
        var dummy = 0
        var track = -1
        var muxStarted = false
        val info = MediaCodec.BufferInfo()
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val e = MediaCodec.createEncoderByType("video/avc")
            enc = e
            e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surf = e.createInputSurface()
            e.start()
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m

            fun drain(end: Boolean) {
                var idle = 0
                while (true) {
                    val idx = e.dequeueOutputBuffer(info, if (end) 10_000L else 0L)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!end) return
                        if (++idle > 3000) throw IllegalStateException("The video encoder stopped responding.")
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = m.addTrack(e.outputFormat); m.start(); muxStarted = true
                    } else if (idx >= 0) {
                        idle = 0
                        val buf = e.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxStarted && buf != null) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            m.writeSampleData(track, buf, info)
                        }
                        e.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }

            // ---- EGL on the encoder's input surface
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            check(EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { "EGL init failed" }
            val cfgAttr = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0) && num[0] > 0) { "No suitable EGL config" }
            ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            esf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], surf, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(dpy, esf, esf, ctx)) { "EGL makeCurrent failed" }

            // ---- shader program + quad
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG)
            prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs); GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            val lk = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, lk, 0)
            check(lk[0] != 0) { "Shader link error: " + GLES20.glGetProgramInfoLog(prog) }
            GLES20.glUseProgram(prog)
            val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
            quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); quad.position(0)
            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
            GLES20.glViewport(0, 0, w, h)
            val locs = HashMap<String, Int>()
            fun loc(n: String) = locs.getOrPut(n) { GLES20.glGetUniformLocation(prog, n) }
            fun f1(n: String, v: Float) = GLES20.glUniform1f(loc(n), v)
            fun f3(n: String, v: FloatArray) = GLES20.glUniform3f(loc(n), v[0], v[1], v[2])

            val dt = IntArray(1)
            GLES20.glGenTextures(1, dt, 0); dummy = dt[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummy)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))

            GLES20.glUniform1i(loc("uTexA"), 0)
            GLES20.glUniform1i(loc("uTexB"), 1)
            f1("uTrans", (TRANS_IDS[st.transition] ?: 0).toFloat())
            GLES20.glUniform2f(loc("uRes"), w.toFloat(), h.toFloat())
            f1("uBlur", (st.bgBlur * h / 1080.0 * 1.5).toFloat())
            f1("uDim", minOf(st.bgDim, 0.9).toFloat())
            f1("uVig", if (st.vignette) 1f else 0f)
            f1("uGrain", (st.grain * 150.0 / 255.0).toFloat())
            val g = GRADE_TABLE[st.grade] ?: GRADE_TABLE.getValue("None")
            f3("uShadow", g.shadow); f3("uMid", g.mid); f3("uHigh", g.high)
            f1("uCon", g.con); f1("uSat", g.sat); f1("uLift", g.lift)

            fun texOf(i: Int): Tex? {
                cache[i]?.let { return it }
                val sc = tl.slots[i].src
                if (sc.kind != "image" || sc.path.isEmpty() || !File(sc.path).isFile) return null
                val t = loadTexture(sc.path, w, h) ?: return null
                cache[i] = t
                return t
            }

            fun bindLayer(unit: Int, idx: Int, localT: Double, suffix: String, seedAng: Double) {
                val tex = texOf(idx)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex?.id ?: dummy)
                val sc = tl.slots[idx].src
                val prog01 = (localT / maxOf(tl.lenOf(idx), 0.001)).coerceIn(0.0, 1.0)
                val rect = if (tex != null) {
                    GpuPlan.finalRect(GpuPlan.coverRect(tex.w, tex.h, w, h), GpuPlan.kenBurns(idx, prog01, st.kenBurns))
                } else doubleArrayOf(0.0, 0.0, 1.0, 1.0)
                GLES20.glUniform4f(loc("uRect$suffix"), rect[0].toFloat(), rect[1].toFloat(), rect[2].toFloat(), rect[3].toFloat())
                f1("uKind$suffix", if (tex != null) 1f else 0f)
                val cols = (sc.colors + listOf("#141E30", "#243B55"))
                f3("u${suffix}0", rgb(cols[0], "#141E30")); f3("u${suffix}1", rgb(cols[1], "#243B55"))
                f1("uAng$suffix", (0.6 + idx * 1.7 + localT * 0.08 + seedAng).toFloat())
            }

            for (f in 0 until frames) {
                if (cancelled()) throw CancellationException("Cancelled")
                val t = t0 + f.toDouble() / fps
                val fr = tl.frameAt(t)
                bindLayer(0, fr.a, fr.aT, "A", 0.0)
                if (fr.b >= 0) bindLayer(1, fr.b, fr.bT, "B", 0.0)
                f1("uMix", fr.mix.toFloat())
                f1("uSeed", (f % 97) * 0.37f)
                for (k in cache.keys.toList()) if (k != fr.a && k != fr.b) { GLES20.glDeleteTextures(1, intArrayOf(cache.remove(k)!!.id), 0) }
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(dpy, esf, f * 1_000_000_000L / fps)
                EGL14.eglSwapBuffers(dpy, esf)
                drain(false)
                if (f % 10 == 0) onProgress(f * 100.0 / frames)
            }
            e.signalEndOfInputStream()
            drain(true)
            check(muxStarted) { "The video encoder produced no output." }
        } finally {
            try { if (dpy != EGL14.EGL_NO_DISPLAY) {
                for (t in cache.values) GLES20.glDeleteTextures(1, intArrayOf(t.id), 0)
                if (dummy != 0) GLES20.glDeleteTextures(1, intArrayOf(dummy), 0)
                if (prog != 0) GLES20.glDeleteProgram(prog)
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (esf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, esf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            } } catch (_: Throwable) {}
            try { enc?.stop() } catch (_: Throwable) {}
            try { enc?.release() } catch (_: Throwable) {}
            try { surf?.release() } catch (_: Throwable) {}
            try { if (muxStarted) mux?.stop() } catch (_: Throwable) {}
            try { mux?.release() } catch (_: Throwable) {}
        }
    }
}
