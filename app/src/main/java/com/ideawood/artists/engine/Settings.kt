package com.ideawood.artists.engine

import android.content.Context
import com.ideawood.artists.core.VisualsApi

/** Small private settings (kept in app storage, never in the repo or the APK). */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var visualsKey: String
        get() = sp.getString("visuals_key", "") ?: ""
        set(v) { sp.edit().putString("visuals_key", v.trim().trim('"', '\'').trim()).apply() }

    /** Fast mode: no temperature fall-back / extra sampling in Whisper (much quicker on songs, tiny accuracy cost). */
    var speechFast: Boolean
        get() = sp.getBoolean("speech_fast", true)
        set(v) { sp.edit().putBoolean("speech_fast", v).apply() }

    /** Low-memory mode: flash attention (same results, smaller buffers) when this engine build supports it. */
    var speechLowMem: Boolean
        get() = sp.getBoolean("speech_low_mem", true)
        set(v) { sp.edit().putBoolean("speech_low_mem", v).apply() }

    var visualsBase: String
        get() = sp.getString("visuals_base", VisualsApi.DEFAULT_BASE) ?: VisualsApi.DEFAULT_BASE
        set(v) { sp.edit().putString("visuals_base", v.trim().trimEnd('/')).apply() }
}
