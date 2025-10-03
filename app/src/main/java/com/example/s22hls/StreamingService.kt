package com.example.s22hls

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.s22hls.media.Pipeline

class StreamingService : Service() {

    private var pipeline: Pipeline? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val chanId = "streaming"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, "Streaming", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(chan)
        }
        val notif = NotificationCompat.Builder(this, chanId)
            .setContentTitle("Transmitindo")
            .setContentText("HLS ativo")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url") ?: return START_NOT_STICKY
        val camId = intent.getIntExtra("cameraId", 0)
        val res = intent.getStringExtra("res") ?: "720p"
        val vBitrate = intent.getIntExtra("vBitrate", 800_000)
        val aKbps = intent.getIntExtra("aKbps", 96)

        pipeline?.stop()
        pipeline = Pipeline(this, url, camId, res, vBitrate, aKbps).apply { start() }
        return START_STICKY
    }

    override fun onDestroy() {
        pipeline?.stop()
        super.onDestroy()
    }
}
