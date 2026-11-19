package com.tymur.raccoontoy

import android.content.Context
import com.tymur.raccoontoy.core.ConvertOptions
import com.tymur.raccoontoy.core.LumaFrames
import com.tymur.raccoontoy.core.MatrixConverter
import com.tymur.raccoontoy.core.MatrixFrames

/** Loads the GIF bundled in app/src/main/assets/raccoon.gif and converts it to matrix frames. */
class ToyStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("toy", Context.MODE_PRIVATE)

    /** 0.1..1.0 overall LED brightness. */
    var brightness: Float
        get() = prefs.getFloat("brightness", 1.0f)
        set(v) = prefs.edit().putFloat("brightness", v).apply()

    /** Decoded grayscale source, or null if the GIF is missing/broken. */
    fun loadLuma(): LumaFrames? = runCatching {
        LumaFrames.fromGif(app.assets.open(GIF_ASSET).use { it.readBytes() })
    }.getOrNull()

    fun convert(luma: LumaFrames): MatrixFrames =
        MatrixConverter.convert(luma, ConvertOptions(zoom = ZOOM, brightness = brightness, contrast = GAMMA))

    /** Converted frames with the saved settings, or a placeholder animation if there's no GIF. */
    fun loadFrames(): MatrixFrames = loadLuma()?.let { convert(it) } ?: MatrixFrames.placeholder()

    companion object {
        const val GIF_ASSET = "raccoon.gif"

        /** Fixed zoom: 1.1 = 110%. */
        const val ZOOM = 1.1f

        /** Tone curve: below 1.0 lifts mid-tones so the LEDs look brighter (the old default was 1.6). */
        const val GAMMA = 0.8f
    }
}
