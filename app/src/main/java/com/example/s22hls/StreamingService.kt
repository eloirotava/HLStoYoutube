package com.example.s22hls

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class StreamingService : Service() {

    companion object {
        const val ACTION_START = "com.example.s22hls.action.START"
        const val ACTION_STOP  = "com.example.s22hls.action.STOP"

        const val EXTRA_CAMERA_ID = "cameraId"
        const val EXTRA_RESOLUTION = "resolution"
        const val EXTRA_VIDEO_BITRATE_KBPS = "videoBitrateKbps"
        const val EXTRA_OUTPUT_URL = "outputUrl"

        private const val CHANNEL_ID = "streaming"
        private const val NOTIF_ID = 42
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() { val service get() = this@StreamingService }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming(intent)
            ACTION_STOP  -> stopStreaming()
        }
        return START_STICKY
    }

    private fun startStreaming(intent: Intent) {
        val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID) ?: return
        val resolution = intent.getStringExtra(EXTRA_RESOLUTION) ?: "720p"
        val kbps = intent.getIntExtra(EXTRA_VIDEO_BITRATE_KBPS, 1200)
        val url = intent.getStringExtra(EXTRA_OUTPUT_URL) ?: return

        startForegroundWithNotification("Preparando…")

        scope.launch {
            try {
                val pipeline = media.Pipeline(
                    service = this@StreamingService,
                    cameraId = cameraId,
                    resolution = resolution,
                    videoBitrateKbps = kbps,
                    outputUrl = url
                )
                pipeline.start()
                updateNotification("Transmitindo")
                // Keep running until cancelled
                pipeline.awaitUntilStopped()
            } catch (t: Throwable) {
                Log.e("StreamingService", "Erro na transmissão", t)
                updateNotification("Erro: ${t.message}")
                stopSelf()
            }
        }
    }

    private fun stopStreaming() {
        updateNotification("Parando…")
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("S22HlsStreamer")
            .setContentText(text)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun updateNotification(text: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("S22HlsStreamer")
            .setContentText(text)
            .setOngoing(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notif)
    }
}
