package com.alxenavcam.ui

import android.Manifest
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.alxenavcam.data.media.SourceType
import com.alxenavcam.processing.AspectRatioPreset
import com.alxenavcam.processing.ScalingMode
import com.alxenavcam.render.GLPipelineRenderer
import com.alxenavcam.util.AppLogger
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val showOverlay by viewModel.showOverlay.collectAsState()
    val errorMsg by viewModel.errorMessage.collectAsState()
    val micEnabled by viewModel.micEnabled.collectAsState()
    val logs by AppLogger.logs.collectAsState()

    var showDebugDialog by remember { mutableStateOf(false) }
    var showVcamLimitationDialog by remember { mutableStateOf(false) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.loadVideoUri(it) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.loadPhotoUri(it) }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.loadExternalAudioUri(it) }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRealCamera()
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.toggleMicrophone(!micEnabled)
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ALXENAVCAM", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.toggleOverlay() }) {
                        Icon(
                            imageVector = if (showOverlay) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Overlay"
                        )
                    }
                    IconButton(onClick = { showVcamLimitationDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Virtual Camera Info")
                    }
                    IconButton(onClick = { showDebugDialog = true }) {
                        Icon(Icons.Default.BugReport, contentDescription = "Debug Logs")
                    }
                }
            )
        }
    ) { padding ->
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                PreviewBox(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    viewModel = viewModel,
                    showOverlay = showOverlay
                )
                ControlPanels(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    viewModel = viewModel,
                    onPickVideo = { videoPicker.launch("video/*") },
                    onPickPhoto = { photoPicker.launch("image/*") },
                    onPickAudio = { audioPicker.launch("audio/*") },
                    onStartCamera = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    onToggleMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                PreviewBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(290.dp),
                    viewModel = viewModel,
                    showOverlay = showOverlay
                )
                ControlPanels(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    viewModel = viewModel,
                    onPickVideo = { videoPicker.launch("video/*") },
                    onPickPhoto = { photoPicker.launch("image/*") },
                    onPickAudio = { audioPicker.launch("audio/*") },
                    onStartCamera = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    onToggleMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
        }
    }

    errorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Perhatian / Error") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("OK") }
            }
        )
    }

    if (showVcamLimitationDialog) {
        val report = viewModel.virtualCamReport
        AlertDialog(
            onDismissRequest = { showVcamLimitationDialog = false },
            title = { Text("Status Virtual Camera Non-Root") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(report.explanation, fontSize = 13.sp)
                    HorizontalDivider()
                    Text("Kamera Sistem Terdeteksi:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    report.detectedPhysicalCameras.forEach { cam ->
                        Text("• $cam", fontSize = 12.sp)
                    }
                    HorizontalDivider()
                    Text(
                        "Endpoint Output Aktif:\n${viewModel.videoOutput.getCapabilities().endpointOrPath}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showVcamLimitationDialog = false }) { Text("Tutup") }
            }
        )
    }

    if (showDebugDialog) {
        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            title = { Text("Realtime Debug Logs (ALXENAVCAM)") },
            text = {
                LazyColumn(modifier = Modifier.height(340.dp)) {
                    items(logs) { item ->
                        Text(
                            text = "${item.timestamp} [${item.category}] ${item.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDebugDialog = false }) { Text("Tutup") }
            },
            dismissButton = {
                TextButton(onClick = { AppLogger.clear() }) { Text("Clear") }
            }
        )
    }
}

@Composable
private fun PreviewBox(
    modifier: Modifier,
    viewModel: MainViewModel,
    showOverlay: Boolean
) {
    val transform by viewModel.transform.collectAsState()
    val metadata by viewModel.metadata.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val isOutputRunning by viewModel.isOutputRunning.collectAsState()
    val micEnabled by viewModel.micEnabled.collectAsState()
    val sourceType by viewModel.sourceType.collectAsState()

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    viewModel.updateTransform { current ->
                        val newZoom = (current.zoom * zoomChange).coerceIn(1.0f, current.maxZoom)
                        val newPanX = (current.panX + pan.x / 500f).coerceIn(-1f, 1f)
                        val newPanY = (current.panY - pan.y / 500f).coerceIn(-1f, 1f)
                        current.copy(zoom = newZoom, panX = newPanX, panY = newPanY)
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    lateinit var customRenderer: GLPipelineRenderer
                    customRenderer = GLPipelineRenderer(
                        onSurfaceReady = { surface ->
                            viewModel.bindRendererCallbacks(
                                surface = surface,
                                requestRender = { requestRender() },
                                sendPhoto = { bmp ->
                                    customRenderer.setPhotoBitmap(bmp)
                                    requestRender()
                                }
                            )
                        },
                        onFrameMetrics = { fps, renderMs ->
                            viewModel.onRenderMetrics(fps, renderMs)
                        },
                        onFrameBytesAvailable = { rgbaBuf, w, h ->
                            viewModel.videoOutput.submitFrame(rgbaBuf, w, h, System.nanoTime())
                        }
                    )
                    setRenderer(customRenderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    tag = customRenderer
                }
            },
            update = { glView ->
                val customRenderer = glView.tag as? GLPipelineRenderer
                customRenderer?.let {
                    it.transformState = transform
                    it.isPhotoMode = (sourceType == SourceType.PHOTO)
                    if (metadata.width > 0 && metadata.height > 0) {
                        it.sourceWidth = metadata.width
                        it.sourceHeight = metadata.height
                    }
                    it.enableReadbackForOutput = isOutputRunning
                    glView.requestRender()
                }
            }
        )

        if (showOverlay) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = "FPS: ${metrics.currentFps} | Drop: ${metrics.droppedFrames} | RAM: ${metrics.memoryUsageMb}MB",
                    color = if (metrics.lowFpsWarning) Color.Yellow else Color.Green,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Src: ${metadata.width}x${metadata.height} (${metadata.codecName})",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "HW Accel: ${metadata.isHardwareAccelerated} | Decode: ${"%.1f".format(metrics.decodeTimeMs)}ms",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "AudioBuf: ${metrics.audioBufferBytes}B | MicMix: ${if (micEnabled) "ON" else "OFF"}",
                    color = Color.Cyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ControlPanels(
    modifier: Modifier,
    viewModel: MainViewModel,
    onPickVideo: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickAudio: () -> Unit,
    onStartCamera: () -> Unit,
    onToggleMic: () -> Unit
) {
    val transform by viewModel.transform.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLooping by viewModel.isLooping.collectAsState()
    val speed by viewModel.playbackSpeed.collectAsState()
    val posMs by viewModel.currentPositionMs.collectAsState()
    val durMs by viewModel.totalDurationMs.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val micEnabled by viewModel.micEnabled.collectAsState()
    val outputRes by viewModel.outputResHeight.collectAsState()
    val targetFps by viewModel.targetFps.collectAsState()
    val isOutputRunning by viewModel.isOutputRunning.collectAsState()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SOURCE", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickVideo, modifier = Modifier.weight(1f)) { Text("Video") }
                    Button(onClick = onPickPhoto, modifier = Modifier.weight(1f)) { Text("Photo") }
                    OutlinedButton(onClick = onStartCamera, modifier = Modifier.weight(1f)) { Text("Camera") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickAudio, modifier = Modifier.weight(1f)) { Text("Audio File") }
                    FilterChip(
                        selected = micEnabled,
                        onClick = onToggleMic,
                        label = { Text(if (micEnabled) "Mic Mix: ON" else "Microphone") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TRANSFORM (REALTIME GPU)", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                Text("Aspect Ratio:", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AspectRatioPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = transform.aspectRatio == preset,
                            onClick = { viewModel.updateTransform { it.copy(aspectRatio = preset) } },
                            label = { Text(preset.label, fontSize = 11.sp) }
                        )
                    }
                }

                Text("Scaling Mode:", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScalingMode.entries.forEach { mode ->
                        FilterChip(
                            selected = transform.scalingMode == mode,
                            onClick = { viewModel.updateTransform { it.copy(scalingMode = mode) } },
                            label = { Text(mode.label, fontSize = 11.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0, 90, 180, 270).forEach { deg ->
                            FilterChip(
                                selected = transform.rotationDegrees == deg,
                                onClick = { viewModel.updateTransform { it.copy(rotationDegrees = deg) } },
                                label = { Text("${deg}°", fontSize = 11.sp) }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = transform.flipHorizontal,
                            onCheckedChange = { c -> viewModel.updateTransform { it.copy(flipHorizontal = c) } }
                        )
                        Text("Flip H", fontSize = 12.sp)
                        Checkbox(
                            checked = transform.flipVertical,
                            onCheckedChange = { c -> viewModel.updateTransform { it.copy(flipVertical = c) } }
                        )
                        Text("Flip V", fontSize = 12.sp)
                    }
                }

                Text("Zoom: ${"%.2f".format(transform.zoom)}x", fontSize = 12.sp)
                Slider(
                    value = transform.zoom,
                    onValueChange = { z -> viewModel.updateTransform { it.copy(zoom = z) } },
                    valueRange = 1.0f..transform.maxZoom
                )

                Text("Position X: ${"%.2f".format(transform.panX)}", fontSize = 12.sp)
                Slider(
                    value = transform.panX,
                    onValueChange = { x -> viewModel.updateTransform { it.copy(panX = x) } },
                    valueRange = -1.0f..1.0f
                )
                Text("Position Y: ${"%.2f".format(transform.panY)}", fontSize = 12.sp)
                Slider(
                    value = transform.panY,
                    onValueChange = { y -> viewModel.updateTransform { it.copy(panY = y) } },
                    valueRange = -1.0f..1.0f
                )

                OutlinedButton(
                    onClick = {
                        viewModel.updateTransform {
                            it.copy(zoom = 1f, panX = 0f, panY = 0f, rotationDegrees = 0, flipHorizontal = false, flipVertical = false)
                        }
                    }
                ) {
                    Text("Reset Transform")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PLAYBACK", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = { viewModel.togglePlayPause() }) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause")
                    }
                    IconButton(onClick = { viewModel.restartPlayback() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Restart")
                    }
                    FilterChip(
                        selected = isLooping,
                        onClick = { viewModel.setLooping(!isLooping) },
                        label = { Text("Loop") }
                    )
                    Text(
                        text = "${formatMs(posMs)} / ${formatMs(durMs)}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (durMs > 0) {
                    Slider(
                        value = posMs.toFloat().coerceIn(0f, durMs.toFloat()),
                        onValueChange = { viewModel.seekTo(it.toLong()) },
                        valueRange = 0f..durMs.toFloat()
                    )
                }

                Text("Speed: ${speed}x", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { sp ->
                        FilterChip(
                            selected = speed == sp,
                            onClick = { viewModel.setSpeed(sp) },
                            label = { Text("${sp}x", fontSize = 10.sp) }
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AUDIO PIPELINE", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume: ${(volume * 100).roundToInt()}%", modifier = Modifier.weight(1f), fontSize = 12.sp)
                    Checkbox(checked = isMuted, onCheckedChange = { viewModel.setMuted(it) })
                    Text("Mute", fontSize = 12.sp)
                }
                Slider(
                    value = volume,
                    onValueChange = { viewModel.setVolume(it) },
                    valueRange = 0f..1.5f
                )
                Text("Audio Level Meter:", fontSize = 11.sp)
                LinearProgressIndicator(
                    progress = { audioLevel.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OUTPUT", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(360, 480, 720, 1080).forEach { res ->
                        FilterChip(
                            selected = outputRes == res,
                            onClick = { viewModel.setOutputPreset(res, targetFps) },
                            label = { Text("${res}p") }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 60).forEach { fps ->
                        FilterChip(
                            selected = targetFps == fps,
                            onClick = { viewModel.setOutputPreset(outputRes, fps) },
                            label = { Text("$fps FPS") }
                        )
                    }
                }

                val ratio = transform.effectiveTargetRatio()
                val outW = if (ratio < 1f) outputRes else (outputRes * ratio).roundToInt()
                val outH = if (ratio < 1f) (outputRes / ratio).roundToInt() else outputRes

                Text(
                    text = "Output: $outW × $outH | $targetFps FPS | ${transform.aspectRatio.label}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )

                Button(
                    onClick = { viewModel.toggleOutput() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isOutputRunning) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isOutputRunning) "[ STOP OUTPUT ]" else "[ START OUTPUT ]", fontWeight = FontWeight.Bold)
                }

                if (isOutputRunning) {
                    Text(
                        text = "Stream Aktif: ${viewModel.videoOutput.getCapabilities().endpointOrPath}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
