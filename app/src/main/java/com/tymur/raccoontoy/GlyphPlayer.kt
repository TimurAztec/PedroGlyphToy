package com.tymur.raccoontoy

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.tymur.raccoontoy.core.MatrixFrames
import java.util.concurrent.Executors

/** Connects to the Glyph Matrix and loops the bundled GIF. Used by the toy and the call service. */
class GlyphPlayer(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val store = ToyStore(app)

    private var gmm: GlyphMatrixManager? = null
    private var frames: MatrixFrames? = null
    private var index = 0
    private var running = false

    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(name: ComponentName?) {
            gmm?.register(Glyph.DEVICE_23112)
            if (frames != null) play() else io.execute {
                val loaded = store.loadFrames()
                main.post { frames = loaded; if (gmm != null) play() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            halt()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val f = frames ?: return
            if (!running) return
            if (index >= f.count) index = 0
            push(f.frames[index])
            val delay = f.delaysMs[index].toLong().coerceAtLeast(16L)
            index++
            main.postDelayed(this, delay)
        }
    }

    /** Connects to the matrix; playback starts once the connection is ready. */
    fun start() {
        if (gmm != null) return
        frames = null // reload so changed zoom/brightness apply
        gmm = GlyphMatrixManager.getInstance(app).also { it.init(callback) }
    }

    /** Stops playback, turns the LEDs off and disconnects. Safe to call repeatedly. */
    fun stop() {
        halt()
        val m = gmm ?: return
        gmm = null
        try {
            m.turnOff()
            m.unInit()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown failed", e)
        }
    }

    fun release() {
        stop()
        io.shutdownNow()
    }

    private fun play() {
        if (running) return
        running = true
        index = 0
        main.post(tick)
    }

    private fun halt() {
        running = false
        main.removeCallbacks(tick)
    }

    private fun push(frame: IntArray) {
        try {
            gmm?.setMatrixFrame(frame)
        } catch (e: Exception) {
            Log.w(TAG, "setMatrixFrame failed", e)
        }
    }

    private companion object {
        const val TAG = "GlyphPlayer"
    }
}
