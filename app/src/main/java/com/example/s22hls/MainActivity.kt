package com.example.s22hls

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etCameraId: EditText
    private lateinit var etRes: EditText
    private lateinit var etBitrate: EditText
    private lateinit var etAudioK: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        etCameraId = findViewById(R.id.etCameraId)
        etRes = findViewById(R.id.etRes)
        etBitrate = findViewById(R.id.etBitrate)
        etAudioK = findViewById(R.id.etAudioK)
        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!hasPermissions()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    100
                )
                return@setOnClickListener
            }
            startStreaming()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, StreamingService::class.java))
            tvStatus.text = "Parado"
        }
    }

    private fun hasPermissions(): Boolean {
        return (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

    private fun startStreaming() {
        val url = etUrl.text.toString().trim()
        val camId = etCameraId.text.toString().ifEmpty { "0" }.toInt()
        val res = etRes.text.toString().ifEmpty { "720p" }
        val br = etBitrate.text.toString().ifEmpty { "800000" }.toInt()
        val ak = etAudioK.text.toString().ifEmpty { "96" }.toInt()

        val i = Intent(this, StreamingService::class.java).apply {
            putExtra("url", url)
            putExtra("cameraId", camId)
            putExtra("res", res)
            putExtra("vBitrate", br)
            putExtra("aKbps", ak)
        }
        ContextCompat.startForegroundService(this, i)
        tvStatus.text = "Iniciando..."
    }
}
