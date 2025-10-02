package com.example.s22hls.media

import android.app.Service
import kotlinx.coroutines.*

class Pipeline(
    private val service: Service,
    private val cameraId: String,
    private val resolution: String,
    private val videoBitrateKbps: Int,
    private val outputUrl: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun start() = withContext(Dispatchers.Default) {
        // TODO:
        // 1) Open Camera2 with cameraId and configure Surface(s) for the chosen resolution
        // 2) Create MediaCodec video encoder (HEVC) at videoBitrateKbps, 30fps, GOP=2s
        // 3) Create AudioRecord + AAC-LC encoder at 96 kbps, 48 kHz, stereo
        // 4) Mux into HLS (2s segments) and HTTP PUT to outputUrl (playlist + segments)
        // Provide minimal backpressure + retries
    }

    suspend fun awaitUntilStopped() {
        try {
            while (isActive) delay(1000)
        } finally {
            // TODO: stop encoders, camera, post #EXT-X-ENDLIST if desired
        }
    }
}
