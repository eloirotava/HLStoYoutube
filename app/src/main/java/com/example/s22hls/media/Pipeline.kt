package com.example.s22hls.media

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

class Pipeline(
    private val ctx: Context,
    private val baseUrl: String,
    private val cameraId: Int,
    private val resolution: String,
    private val videoBitrate: Int,
    private val audioKbps: Int
) {
    private val TAG = "PipelineUploader"
    private val scope = CoroutineScope(Dispatchers.Default)
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()

    private var camDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRec: AudioRecord? = null

    // Thread/Looper para Camera2
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    @Volatile private var running = false

    private val workDir = File(ctx.cacheDir, "hls-uploader").apply { mkdirs() }
    private var segIndex = 0
    private val playlist = HlsPlaylist(
        targetDurationSec = 3,
        maxSegments = 6,
        addIndependentSegmentsTag = true,
        playlistTypeEvent = false
    )

    private var vpsSpsPps: ByteArray? = null

    fun start() {
        running = true
        Log.i(TAG, "Started uploader pipeline")

        // Handler thread para callbacks da Camera2
        camThread = HandlerThread("Camera2Thread").apply { start() }
        camHandler = Handler(camThread!!.looper)

        scope.launch {
            openCameraAndStart()
        }
    }

    fun stop() {
        running = false
        try { session?.close() } catch(_:Throwable){}
        try { camDevice?.close() } catch(_:Throwable){}
        try { videoCodec?.stop(); videoCodec?.release() } catch(_:Throwable){}
        try { audioCodec?.stop(); audioCodec?.release() } catch(_:Throwable){}
        try { audioRec?.stop(); audioRec?.release() } catch(_:Throwable){}
        scope.cancel()

        try {
            camThread?.quitSafely()
            camThread?.join()
        } catch (_:Throwable) {}
        camHandler = null
        camThread = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCameraAndStart() = withContext(Dispatchers.Default) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cam = cm.cameraIdList.getOrNull(cameraId) ?: cm.cameraIdList.first()

        val w = when (resolution) {
            "1080p" -> 1920
            "360p"  -> 640
            else    -> 1280
        }
        val h = when (resolution) {
            "1080p" -> 1080
            "360p"  -> 360
            else    -> 720
        }

        // Encoder de vídeo (HEVC)
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // IDR a cada ~2s
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        }
        val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).also {
            it.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        videoCodec = vCodec
        val inputSurface = vCodec.createInputSurface()

        // Encoder de áudio (AAC LC 48kHz estéreo)
        val sr = 48000
        val ch = 2
        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, (audioKbps * 1000).coerceAtLeast(96_000))
        }
        val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        audioCodec = aCodec

        // AudioRecord (PCM 16-bit)
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .build()
        audioRec = rec

        // Abre a câmera
        val open = CompletableDeferred<Unit>()
        cm.openCamera(cam, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                Log.i(TAG, "Camera onOpened id=${device.id}")
                camDevice = device
                open.complete(Unit)
            }
            override fun onDisconnected(device: CameraDevice) { }
            override fun onError(device: CameraDevice, error: Int) {
                open.completeExceptionally(RuntimeException("cam error $error"))
            }
        }, camHandler)
        open.await()

        // Inicia codecs e captura de áudio
        vCodec.start()
        aCodec.start()
        rec.startRecording()

        // Sessão de captura
        val outputs = listOf(inputSurface)
        val sessionReady = CompletableDeferred<Unit>()
        camDevice!!.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = camDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(inputSurface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                }
                s.setRepeatingRequest(req.build(), null, camHandler)
                Log.i(TAG, "CaptureSession configured")
                sessionReady.complete(Unit)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                sessionReady.completeExceptionally(RuntimeException("session failed"))
            }
        }, camHandler)
        sessionReady.await()

        // Loops
        launch { audioLoop(aCodec, rec) }
        launch { videoLoop(vCodec) }
        launch { rotationAndUploadLoop() }
    }

    private data class Encoded(val data: ByteArray, val ptsUs: Long, val dtsUs: Long = -1, val flags: Int = 0)

    private val videoQueue = ArrayDeque<Encoded>()
    private val audioQueue = ArrayDeque<Encoded>()

    private suspend fun videoLoop(codec: MediaCodec) = withContext(Dispatchers.Default) {
        val bufInfo = MediaCodec.BufferInfo()
        while (running) {
            val outIndex = codec.dequeueOutputBuffer(bufInfo, 10_000)
            if (outIndex >= 0) {
                val buf = codec.getOutputBuffer(outIndex)!!
                val data = ByteArray(bufInfo.size)
                buf.position(bufInfo.offset)
                buf.get(data)
                val isConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (isConfig) {
                    vpsSpsPps = data
                    Log.i(TAG, "Video format changed; got VPS/SPS/PPS (${data.size} bytes)")
                } else {
                    val isKey = (bufInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    synchronized(videoQueue) {
                        videoQueue.addLast(
                            Encoded(
                                data,
                                bufInfo.presentationTimeUs,
                                bufInfo.presentationTimeUs,
                                if (isKey) 1 else 0
                            )
                        )
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    private suspend fun audioLoop(codec: MediaCodec, rec: AudioRecord) = withContext(Dispatchers.Default) {
        val encInfo = MediaCodec.BufferInfo()
        val pcmBuf = ShortArray(2048)
        val tmp = ByteArray(pcmBuf.size * 2)
        while (running) {
            val n = rec.read(pcmBuf, 0, pcmBuf.size)
            if (n <= 0) continue
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inBuf = codec.getInputBuffer(inIndex)!!
                inBuf.clear()
                var p = 0
                for (i in 0 until n) {
                    val v = pcmBuf[i].toInt()
                    tmp[p++] = (v and 0xFF).toByte()
                    tmp[p++] = ((v ushr 8) and 0xFF).toByte()
                }
                inBuf.put(tmp, 0, p)
                val tUs = System.nanoTime() / 1000
                codec.queueInputBuffer(inIndex, 0, p, tUs, 0)
            }
            var outIndex = codec.dequeueOutputBuffer(encInfo, 0)
            while (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)!!
                val data = ByteArray(encInfo.size)
                outBuf.position(encInfo.offset)
                outBuf.get(data)
                synchronized(audioQueue) {
                    audioQueue.addLast(Encoded(data, encInfo.presentationTimeUs))
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(encInfo, 0)
            }
        }
    }

    private suspend fun rotationAndUploadLoop() = withContext(Dispatchers.Default) {
        val targetDurSec = 2.5 // ~2.5s, TARGETDURATION=3

        var baos = ByteArrayOutputStream()
        var ts = TsWriter(baos)

        var segOpen = false
        var segStartPtsUs: Long = 0L
        var baseUs: Long = 0L
        var lastVideoPtsUsAdj: Long = 0L
        var lastAudioPtsUsAdj: Long = 0L

        fun currentDurSec(): Double =
            if (!segOpen) 0.0 else ((maxOf(lastVideoPtsUsAdj, lastAudioPtsUsAdj)) / 1_000_000.0)

        fun openNewSegment(startPtsUs: Long) {
            baos = ByteArrayOutputStream()
            ts = TsWriter(baos)
            ts.writePatPmt()
            segStartPtsUs = startPtsUs
            baseUs = startPtsUs
            lastVideoPtsUsAdj = 0L
            lastAudioPtsUsAdj = 0L
            segOpen = true
        }

        fun closeAndUploadSegment() {
            if (!segOpen) return
            val segName = "seg_%05d.ts".format(segIndex)
            val segBytes = baos.toByteArray()
            val f = File(workDir, segName)
            FileOutputStream(f).use { it.write(segBytes) }
            putFile(segName, segBytes)

            val dur = currentDurSec().coerceAtLeast(0.1)
            // MEDIA-SEQUENCE = índice do primeiro segmento da janela (a lib calcula)
            playlist.add(segIndex, segName, dur)
            val m3u8 = playlist.toText()
            putFile("live.m3u8", m3u8.toByteArray(Charsets.UTF_8))

            segIndex += 1
            segOpen = false
        }

        while (running) {
            var wroteSomething = false

            // VÍDEO
            synchronized(videoQueue) {
                while (videoQueue.isNotEmpty()) {
                    val e = videoQueue.removeFirst()
                    val isKey = (e.flags == 1)

                    if (!segOpen) {
                        if (!isKey) continue // abre somente em IDR
                        openNewSegment(e.ptsUs)
                    } else {
                        // fecha no próximo keyframe quando já atingiu a duração alvo
                        if (isKey && currentDurSec() >= targetDurSec) {
                            closeAndUploadSegment()
                            openNewSegment(e.ptsUs)
                        }
                    }

                    // normaliza PTS/DTS do segmento
                    var ptsAdj = (e.ptsUs - baseUs).coerceAtLeast(0L)
                    var dtsAdj = (e.dtsUs - baseUs).coerceAtLeast(0L)
                    // sem B-frames: DTS <= PTS
                    if (ptsAdj <= lastVideoPtsUsAdj) ptsAdj = lastVideoPtsUsAdj + 1
                    if (dtsAdj > ptsAdj) dtsAdj = ptsAdj
                    lastVideoPtsUsAdj = ptsAdj

                    ts.writeVideoAccessUnit(e.data, isKey, ptsAdj, dtsAdj, vpsSpsPps)
                    wroteSomething = true
                }
            }

            // ÁUDIO (só grava se houver segmento aberto)
            synchronized(audioQueue) {
                while (segOpen && audioQueue.isNotEmpty()) {
                    val e = audioQueue.removeFirst()
                    // ignora áudio muito antes do keyframe base
                    if (e.ptsUs < baseUs - 500_000) continue

                    var ptsAdj = (e.ptsUs - baseUs).coerceAtLeast(0L)
                    if (ptsAdj <= lastAudioPtsUsAdj) ptsAdj = lastAudioPtsUsAdj + 1
                    lastAudioPtsUsAdj = ptsAdj

                    ts.writeAudioFrame(e.data, ptsAdj, 48000, 2)
                    wroteSomething = true
                }
                if (!segOpen) audioQueue.clear()
            }

            // fallback de segurança
            if (segOpen && currentDurSec() > 10.0) {
                closeAndUploadSegment()
            }

            if (!wroteSomething) delay(10)
        }

        if (segOpen) closeAndUploadSegment()
    }

    private fun putFile(name: String, bytes: ByteArray) {
        try {
            val url = if (baseUrl.contains("file=")) {
                baseUrl + URLEncoder.encode(name, "UTF-8")
            } else {
                if (baseUrl.endsWith("/")) baseUrl + name else "$baseUrl/$name"
            }
            val req = Request.Builder()
                .url(url)
                .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                Log.i(TAG, "PUT $name -> ${resp.code} ")
                if (!resp.isSuccessful && name.endsWith(".m3u8")) {
                    Log.e(TAG, "Playlist upload failed body=${resp.body?.string()}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "PUT $name failed: ${t.message}")
        }
    }
}
