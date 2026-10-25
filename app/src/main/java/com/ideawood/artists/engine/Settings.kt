package com.ideawood.artists.engine

import android.content.Context
import com.ideawood.artists.core.VisualsApi

/** Small private settings (kept in app storage, never in the repo or the APK). */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var visualsKey: String
        get() = sp.getString("visuals_key", "") ?: ""
        set(v) { sp.edit().putString("visuals_key", v.trim().trim('"', '\'').trim()).apply() }

    var visualsBase: String
        get() = sp.getString("visuals_base", VisualsApi.DEFAULT_BASE) ?: VisualsApi.DEFAULT_BASE
        set(v) { sp.edit().putString("visuals_base", v.trim().trimEnd('/')).apply() }
}
