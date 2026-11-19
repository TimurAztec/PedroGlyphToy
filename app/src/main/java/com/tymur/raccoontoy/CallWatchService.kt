package com.tymur.raccoontoy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/** Foreground service that plays the GIF on the Glyph Matrix while the phone is ringing. */
class CallWatchService : Service() {

    private lateinit var player: GlyphPlayer
    private lateinit var telephony: TelephonyManager
    private var listening = false

    private val callStateCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (state == TelephonyManager.CALL_STATE_RINGING) player.start() else player.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = GlyphPlayer(this)
        telephony = getSystemService(TelephonyManager::class.java)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.watching_calls))
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            telephony.registerTelephonyCallback(mainExecutor, callStateCallback)
            listening = true
        } else {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (listening) telephony.unregisterTelephonyCallback(callStateCallback)
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "call_watch"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CallWatchService::class.java))
        }
    }
}
