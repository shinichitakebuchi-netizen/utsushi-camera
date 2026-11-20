package com.kooky.utsushi

import android.content.Context

/** 設定（GLスレッドからも読むので @Volatile） */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("utsushi", Context.MODE_PRIVATE)

    @Volatile var auto = sp.getBoolean("auto", true)
    @Volatile var meter = sp.getBoolean("meter", true)
    @Volatile var film = sp.getBoolean("film", true)
    @Volatile var grid = sp.getBoolean("grid", true)
    @Volatile var frame = sp.getBoolean("frame", false)
    @Volatile var look = sp.getInt("look", 0).coerceIn(0, LOOKS.size - 1)
    @Volatile var ev = sp.getFloat("ev", 0f).coerceIn(-1.5f, 1.5f)
    @Volatile var aspect = sp.getInt("aspect", 0).coerceIn(0, ASPECTS.size - 1)
    @Volatile var back = sp.getBoolean("back", true)
    @Volatile var af = sp.getString("af", "AF-C") ?: "AF-C"
    @Volatile var mfk = sp.getInt("mfk", MF_N).coerceIn(0, MF_N)

    fun save() {
        sp.edit()
            .putBoolean("auto", auto).putBoolean("meter", meter).putBoolean("film", film)
            .putBoolean("grid", grid).putBoolean("frame", frame).putInt("look", look)
            .putFloat("ev", ev).putInt("aspect", aspect).putBoolean("back", back)
            .putString("af", af).putInt("mfk", mfk)
            .apply()
    }

    companion object {
        const val MF_N = 40
    }
}
