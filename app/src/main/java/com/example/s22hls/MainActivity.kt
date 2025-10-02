package com.example.s22hls

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.s22hls.ui.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var bound by mutableStateOf(false)
    private var service: StreamingService? = null

    private val conn = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as StreamingService.LocalBinder).service
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> /* no-op; UI will reflect current permission status */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    ConfigScreen(
                        onStart = { cfg ->
                            ensurePermissions {
                                val intent = Intent(this, StreamingService::class.java).apply {
                                    action = StreamingService.ACTION_START
                                    putExtra(StreamingService.EXTRA_CAMERA_ID, cfg.cameraId)
                                    putExtra(StreamingService.EXTRA_RESOLUTION, cfg.resolution)
                                    putExtra(StreamingService.EXTRA_VIDEO_BITRATE_KBPS, cfg.videoBitrateKbps)
                                    putExtra(StreamingService.EXTRA_OUTPUT_URL, cfg.outputUrl)
                                }
                                ContextCompat.startForegroundService(this, intent)
                                bindService(Intent(this, StreamingService::class.java), conn, Context.BIND_AUTO_CREATE)
                            }
                        },
                        onStop = {
                            val intent = Intent(this, StreamingService::class.java).apply {
                                action = StreamingService.ACTION_STOP
                            }
                            startService(intent)
                            if (bound) unbindService(conn)
                        }
                    )
                }
            }
        }
    }

    private fun ensurePermissions(after: () -> Unit) {
        val need = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ))
        } else after()
    }

    override fun onDestroy() {
        if (bound) unbindService(conn)
        super.onDestroy()
    }
}
