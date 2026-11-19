package com.tymur.raccoontoy

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger

/** Glyph Toy: loops the bundled GIF on the Phone (3) Glyph Matrix until another toy is selected. */
class RaccoonToyService : Service() {

    private lateinit var player: GlyphPlayer
    private val messenger = Messenger(Handler(Looper.getMainLooper()))

    override fun onCreate() {
        super.onCreate()
        player = GlyphPlayer(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        player.start()
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        player.stop()
        return false
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
