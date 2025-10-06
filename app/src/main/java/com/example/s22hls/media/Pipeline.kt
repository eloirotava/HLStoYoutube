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

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    @Volatile private var running = false

    private val workDir = File(ctx.cacheDir, "hls-uploader").apply { mkdirs() }
    private var segIndex = 0
    private var mediaSeq = 0
    private val playlist = HlsPlaylist(3, 6)

    private var vpsSpsPps: ByteArray? = null

    // >>> NOVO: contador contínuo de amostras (frames por canal) para PTS do áudio
    private var audioSamplesTotal = 0L

    fun start() {
        running = true
        Log.i(TAG, "Started uploader pipeline")

        camThread = HandlerThread("Camera2Thread").apply { start() }
        camHandler = Handler(camThread!!.looper)

        scope.launch { openCameraAndStart() }
    }

    fun stop() {
        running = false
        try { session?.close() } catch (_: Throwable) {}
        try { camDevice?.close() } catch (_: Throwable) {}
        try { videoCodec?.stop(); videoCodec?.release() } catch (_: Throwable) {}
        try { audioCodec?.stop(); audioCodec?.release() } catch (_: Throwable) {}
        try { audioRec?.stop(); audioRec?.release() } catch (_: Throwable) {}
        scope.cancel()

        try {
            camThread?.quitSafely()
            camThread?.join()
        } catch (_: Throwable) {}
        camHandler = null
        camThread = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCameraAndStart() = withContext(Dispatchers.Default) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cam = cm.cameraIdList.getOrNull(cameraId) ?: cm.cameraIdList.first()

        val w = when (resolution) { "1080p" -> 1920; "360p" -> 640; else -> 1280 }
        val h = when (resolution) { "1080p" -> 1080; "360p" -> 360; else -> 720 }

        // Video HEVC
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        }
        val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).also {
            it.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        videoCodec = vCodec
        val inputSurface = vCodec.createInputSurface()

        // Audio AAC LC 48kHz estéreo
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

        // AudioRecord
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

        // Abrir câmera
        val open = CompletableDeferred<Unit>()
        cm.openCamera(cam, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camDevice = device
                open.complete(Unit)
            }
            override fun onDisconnected(device: CameraDevice) {}
            override fun onError(device: CameraDevice, error: Int) {
                open.completeExceptionally(RuntimeException("cam error $error"))
            }
        }, camHandler)
        open.await()

        // Start codecs e captação
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
                    Log.i(TAG, "got VPS/SPS/PPS ${data.size}B")
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

                // >>> ALTERADO: PTS derivado do total de amostras (frames por canal), estável
                val framesPorCanal = n / 2                  // estéreo
                val ptsUs = (audioSamplesTotal * 1_000_000L) / 48_000L
                audioSamplesTotal += framesPorCanal

                codec.queueInputBuffer(inIndex, 0, p, ptsUs, 0)
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
        val targetDurSec = 3.0

        var baos = ByteArrayOutputStream()
        var ts = TsWriter(baos)

        var segOpen = false
        var segFirstPtsUsAdj: Long = 0L
        var segLastPtsUsAdj: Long = 0L

        // base global de tempo (não zera por segmento)
        var globalBaseUs: Long = -1L

        fun openNewSegment() {
            baos = ByteArrayOutputStream()
            ts = TsWriter(baos)
            ts.writePatPmt()
            segOpen = true
            segFirstPtsUsAdj = -1L
            segLastPtsUsAdj = -1L
        }

        fun currentSegDurSec(): Double {
            return if (!segOpen || segFirstPtsUsAdj < 0 || segLastPtsUsAdj < 0) 0.0
            else (segLastPtsUsAdj - segFirstPtsUsAdj).coerceAtLeast(0L) / 1_000_000.0
        }

        fun closeAndUploadSegment() {
            if (!segOpen) return
            val segName = "seg_%05d.ts".format(segIndex++)
            val segBytes = baos.toByteArray()

            // grava local e envia
            val f = File(workDir, segName)
            FileOutputStream(f).use { it.write(segBytes) }
            putFile(segName, segBytes)

            val dur = currentSegDurSec().coerceAtLeast(0.1)
            playlist.add(segName, dur)
            val m3u8 = playlist.toText(mediaSeq)
            putFile("live.m3u8", m3u8.toByteArray(Charsets.UTF_8))
            mediaSeq += 1

            segOpen = false
        }

        while (running) {
            var wroteSomething = false

            // Vídeo
            synchronized(videoQueue) {
                while (videoQueue.isNotEmpty()) {
                    val e = videoQueue.removeFirst()
                    val isKey = (e.flags == 1)

                    if (globalBaseUs < 0 && isKey) {
                        globalBaseUs = e.ptsUs
                    }
                    if (globalBaseUs < 0) continue

                    if (!segOpen) {
                        if (!isKey) continue
                        openNewSegment()
                    } else {
                        if (isKey && currentSegDurSec() >= targetDurSec) {
                            closeAndUploadSegment()
                            openNewSegment()
                        }
                    }

                    var ptsAdj = (e.ptsUs - globalBaseUs).coerceAtLeast(0L)
                    var dtsAdj = (e.dtsUs - globalBaseUs).coerceAtLeast(0L)
                    if (dtsAdj > ptsAdj) dtsAdj = ptsAdj
                    if (segLastPtsUsAdj >= 0 && ptsAdj <= segLastPtsUsAdj) {
                        ptsAdj = segLastPtsUsAdj + 1
                        dtsAdj = ptsAdj
                    }
                    if (segFirstPtsUsAdj < 0) segFirstPtsUsAdj = ptsAdj
                    segLastPtsUsAdj = ptsAdj

                    ts.writeVideoAccessUnit(e.data, isKey, ptsAdj, dtsAdj, vpsSpsPps)
                    wroteSomething = true
                }
            }

            // Áudio
            synchronized(audioQueue) {
                while (segOpen && audioQueue.isNotEmpty()) {
                    val e = audioQueue.removeFirst()
                    if (globalBaseUs < 0) continue
                    var ptsAdj = e.ptsUs - globalBaseUs
                    if (ptsAdj < 0) continue
                    if (segLastPtsUsAdj >= 0 && ptsAdj <= segLastPtsUsAdj) {
                        ptsAdj = segLastPtsUsAdj + 1
                    }
                    segLastPtsUsAdj = ptsAdj
                    if (segFirstPtsUsAdj < 0) segFirstPtsUsAdj = ptsAdj

                    ts.writeAudioFrame(e.data, ptsAdj, 48000, 2)
                    wroteSomething = true
                }
                if (!segOpen) audioQueue.clear()
            }

            if (segOpen && currentSegDurSec() > 8.0) {
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
                Log.i(TAG, "PUT $name -> ${resp.code}")
                if (!resp.isSuccessful && name.endsWith(".m3u8")) {
                    Log.e(TAG, "Playlist upload failed body=${resp.body?.string()}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "PUT $name failed: ${t.message}")
        }
    }
}
