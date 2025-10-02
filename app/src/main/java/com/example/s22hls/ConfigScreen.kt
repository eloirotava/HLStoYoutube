package com.example.s22hls

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore("prefs")

data class Config(
    val cameraId: String,
    val resolution: String,          // "1080p" | "720p" | "360p"
    val videoBitrateKbps: Int,
    val outputUrl: String
)

@Composable
fun ConfigScreen(
    onStart: (Config) -> Unit,
    onStop: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var cameras by remember { mutableStateOf(listOf<Pair<String,String>>()) } // id to label
    var selectedCameraId by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf("720p") }
    var bitrateText by remember { mutableStateOf(TextFieldValue("1500")) }
    var outputUrl by remember { mutableStateOf(TextFieldValue("")) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cameras = enumerateCameras(ctx)
        if (cameras.isNotEmpty()) selectedCameraId = cameras.first().first
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Configuração", style = MaterialTheme.typography.titleLarge)

        // Camera dropdown
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            TextField(
                modifier = Modifier.menuAnchor(),
                value = cameras.find { it.first == selectedCameraId }?.second ?: "Selecionar câmera",
                onValueChange = {},
                readOnly = true,
                label = { Text("Câmera") }
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                cameras.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            selectedCameraId = id
                            expanded = false
                        }
                    )
                }
            }
        }

        // Resolution
        Text("Resolução de saída")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1080p","720p","360p").forEach { r ->
                FilterChip(
                    selected = resolution == r,
                    onClick = { resolution = r },
                    label = { Text(r) }
                )
                Spacer(Modifier.width(4.dp))
            }
        }

        // Bitrate (video only)
        OutlinedTextField(
            value = bitrateText,
            onValueChange = { bitrateText = it },
            label = { Text("Bitrate do vídeo (kb/s)") },
            singleLine = true
        )
        Text("Áudio fixo: AAC-LC estéreo 48 kHz @ 96 kb/s • FPS fixo: 30", style = MaterialTheme.typography.bodySmall)

        // Output URL
        OutlinedTextField(
            value = outputUrl,
            onValueChange = { outputUrl = it },
            label = { Text("URL de saída (YouTube HLS)") },
            singleLine = true
        )

        // Buttons
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                running = true
                onStart(
                    Config(
                        cameraId = selectedCameraId,
                        resolution = resolution,
                        videoBitrateKbps = bitrateText.text.filter { it.isDigit() }.toIntOrNull() ?: 1200,
                        outputUrl = outputUrl.text
                    )
                )
            }, enabled = outputUrl.text.startsWith("https://a.upload.youtube.com/http_upload_hls")) {
                Text("Iniciar")
            }
            OutlinedButton(onClick = { running = false; onStop() }) { Text("Parar") }
        }
    }
}

private fun enumerateCameras(ctx: Context): List<Pair<String,String>> {
    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    return cm.cameraIdList.map { id ->
        val ch = cm.getCameraCharacteristics(id)
        val facing = when(ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "Traseira"
            CameraCharacteristics.LENS_FACING_FRONT -> "Frontal"
            else -> "Outra"
        }
        val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
        val focalDesc = if (focals.isNotEmpty()) "${focals.minOrNull()}–${focals.maxOrNull()}mm" else ""
        val label = "$facing (id=$id) $focalDesc"
        id to label
    }
}
