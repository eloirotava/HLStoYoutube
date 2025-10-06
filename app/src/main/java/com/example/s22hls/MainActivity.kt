package com.example.s22hls.media

import android.Manifest
import android.os.Bundle
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.pedro.rtplibrary.view.OpenGlView
import com.pedro.rtplibrary.rtsp.server.RtspServerFromCamera2
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.encoder.video.VideoEncoder
import com.pedro.encoder.audio.AudioEncoder

class RtspServerActivity : AppCompatActivity(), ConnectCheckerRtsp {

  private lateinit var openGlView: OpenGlView
  private lateinit var rtspServer: RtspServerFromCamera2

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    openGlView = OpenGlView(this) // ou SurfaceView(this)
    setContentView(openGlView)

    ActivityCompat.requestPermissions(
      this,
      arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
      123
    )

    rtspServer = RtspServerFromCamera2(openGlView, this, 8554)
    // Vídeo
    val width = 1280
    val height = 720
    val fps = 30
    val gopSeconds = 3
    val videoBitrate = 800_000 // alvo para VÍDEO (o total com áudio ≈ 900–950 kb/s)

    // H.265:
    rtspServer.setVideoCodec(VideoEncoder.H265)
    // (se quiser H.264: rtspServer.setVideoCodec(VideoEncoder.H264))

    rtspServer.setProtocol(Protocol.TCP) // RTSP por TCP (mais estável para ffmpeg puxar)
    rtspServer.setVideoBitrateOnFly(videoBitrate)
    rtspServer.prepareVideo(width, height, fps, gopSeconds * fps, false, 0, 0)

    // Áudio AAC LC 48k estéreo
    val audioKbps = 96_000
    rtspServer.prepareAudio(48000, true, audioKbps, true, false)

    // Inicia câmera e servidor
    rtspServer.startPreview(Camera2ApiManager.Facing.BACK)
    // caminho será: rtsp://<ip-do-celular>:8554/live
    rtspServer.startServer() // default path "/live"
  }

  override fun onDestroy() {
    super.onDestroy()
    if (rtspServer.isStreaming) rtspServer.stopServer()
    rtspServer.stopPreview()
  }

  // Callbacks RTSP (logs básicos)
  override fun onConnectionStartedRtsp(rtspUrl: String) {}
  override fun onConnectionSuccessRtsp() {}
  override fun onConnectionFailedRtsp(reason: String) {}
  override fun onDisconnectRtsp() {}
  override fun onAuthErrorRtsp() {}
  override fun onAuthSuccessRtsp() {}
}
