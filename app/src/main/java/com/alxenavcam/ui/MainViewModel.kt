package com.alxenavcam.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alxenavcam.audio.AudioPipelineEngine
import com.alxenavcam.data.media.*
import com.alxenavcam.data.preferences.SettingsRepository
import com.alxenavcam.decoder.AudioDecoder
import com.alxenavcam.decoder.DecoderCapabilities
import com.alxenavcam.decoder.VideoDecoder
import com.alxenavcam.decoder.VirtualCamInvestigationReport
import com.alxenavcam.output.MjpegHttpOutput
import com.alxenavcam.output.VideoOutput
import com.alxenavcam.processing.TransformState
import com.alxenavcam.util.AppLogger
import com.alxenavcam.util.LogCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)

    private val _sourceType = MutableStateFlow(SourceType.NONE)
    val sourceType: StateFlow<SourceType> = _sourceType.asStateFlow()

    private val _transform = MutableStateFlow(TransformState())
    val transform: StateFlow<TransformState> = _transform.asStateFlow()

    private val _metadata = MutableStateFlow(MediaMetadataInfo())
    val metadata: StateFlow<MediaMetadataInfo> = _metadata.asStateFlow()

    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLooping = MutableStateFlow(true)
    val isLooping: StateFlow<Boolean> = _isLooping.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _totalDurationMs = MutableStateFlow(0L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _micEnabled = MutableStateFlow(false)
    val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _outputResHeight = MutableStateFlow(720)
    val outputResHeight: StateFlow<Int> = _outputResHeight.asStateFlow()

    private val _targetFps = MutableStateFlow(30)
    val targetFps: StateFlow<Int> = _targetFps.asStateFlow()

    private val _isOutputRunning = MutableStateFlow(false)
    val isOutputRunning: StateFlow<Boolean> = _isOutputRunning.asStateFlow()

    private val _showOverlay = MutableStateFlow(true)
    val showOverlay: StateFlow<Boolean> = _showOverlay.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val virtualCamReport: VirtualCamInvestigationReport =
        DecoderCapabilities.inspectSystemCameraCapabilities(application)

    val videoOutput: VideoOutput = MjpegHttpOutput(8080)

    private var glInputSurface: Surface? = null
    private var photoRefreshJob: Job? = null
    private var requestRenderCallback: (() -> Unit)? = null
    private var sendPhotoToRendererCallback: ((android.graphics.Bitmap) -> Unit)? = null

    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null

    private val audioEngine = AudioPipelineEngine { rms, bufBytes ->
        _audioLevel.value = rms
        _metrics.update { it.copy(audioBufferBytes = bufBytes) }
    }

    private val videoDecoder = VideoDecoder(
        context = application,
        onMetadataReady = { info -> _metadata.value = info },
        onProgressUpdate = { pos, dur ->
            _currentPositionMs.value = pos
            _totalDurationMs.value = dur
        },
        onDecodeStat = { decodeMs, dropped ->
            _metrics.update {
                it.copy(
                    decodeTimeMs = decodeMs,
                    droppedFrames = if (dropped) it.droppedFrames + 1 else it.droppedFrames
                )
            }
            requestRenderCallback?.invoke()
        },
        onError = { msg -> _errorMessage.value = msg }
    )

    private val audioDecoder = AudioDecoder(
        context = application,
        onPcmData = { pcm, size, rate, ch ->
            audioEngine.submitPcmFrame(pcm, size, rate, ch)
        }
    )

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.first().let { saved ->
                _transform.value = saved.transformState
                _outputResHeight.value = saved.outputHeightPreset
                _targetFps.value = saved.targetFps
                _volume.value = saved.volume
                _isMuted.value = saved.isMuted
                _isLooping.value = saved.isLooping
                _playbackSpeed.value = saved.playbackSpeed
            }
        }
    }

    fun bindRendererCallbacks(
        surface: Surface,
        requestRender: () -> Unit,
        sendPhoto: (android.graphics.Bitmap) -> Unit
    ) {
        glInputSurface = surface
        requestRenderCallback = requestRender
        sendPhotoToRendererCallback = sendPhoto
    }

    fun onRenderMetrics(fps: Int, renderMs: Float) {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        _metrics.update {
            it.copy(
                currentFps = fps,
                renderTimeMs = renderMs,
                memoryUsageMb = usedMb,
                lowFpsWarning = (_isPlaying.value && fps in 1..18)
            )
        }
    }

    fun loadVideoUri(uri: Uri) {
        stopCurrentMedia()
        val surface = glInputSurface ?: run {
            _errorMessage.value = "Surface OpenGL belum siap."
            return
        }
        _sourceType.value = SourceType.VIDEO
        _isPlaying.value = true
        videoDecoder.isPaused = false
        videoDecoder.isLooping = _isLooping.value
        videoDecoder.playbackSpeed = _playbackSpeed.value

        audioDecoder.isPaused = false
        audioDecoder.isLooping = _isLooping.value

        videoDecoder.start(uri, surface, viewModelScope)
        audioDecoder.start(uri, viewModelScope)
        persistSettings(uri.toString())
    }

    fun loadPhotoUri(uri: Uri) {
        stopCurrentMedia()
        try {
            val stream = getApplication<Application>().contentResolver.openInputStream(uri)
            val bmp = BitmapFactory.decodeStream(stream)
            stream?.close()
            if (bmp == null) {
                _errorMessage.value = "File foto corrupt atau format tidak didukung."
                return
            }
            _sourceType.value = SourceType.PHOTO
            _isPlaying.value = true
            _metadata.value = MediaMetadataInfo(
                uri = uri,
                displayName = uri.lastPathSegment ?: "Photo",
                mimeType = "image/*",
                width = bmp.width,
                height = bmp.height,
                fps = _targetFps.value.toFloat(),
                codecName = "OpenGL 2D Texture Generator",
                isHardwareAccelerated = true,
                colorFormat = "RGBA_8888",
                decoderStatus = "CONTINUOUS_STREAM",
                thumbnail = bmp
            )
            sendPhotoToRendererCallback?.invoke(bmp)
            startPhotoContinuousLoop()
        } catch (e: Exception) {
            AppLogger.log(LogCategory.ERROR, "Gagal membuka foto", e)
            _errorMessage.value = "Gagal membaca foto: ${e.localizedMessage}"
        }
    }

    private fun startPhotoContinuousLoop() {
        photoRefreshJob?.cancel()
        photoRefreshJob = viewModelScope.launch {
            while (true) {
                if (_isPlaying.value && _sourceType.value == SourceType.PHOTO) {
                    requestRenderCallback?.invoke()
                }
                val intervalMs = (1000L / _targetFps.value.coerceIn(15, 60))
                delay(intervalMs)
            }
        }
    }

    fun loadExternalAudioUri(uri: Uri) {
        audioDecoder.stop()
        audioDecoder.isPaused = false
        audioDecoder.isLooping = _isLooping.value
        audioDecoder.start(uri, viewModelScope)
        AppLogger.log(LogCategory.AUDIO, "Memutar file audio eksternal: $uri")
    }

    @SuppressLint("MissingPermission")
    fun startRealCamera() {
        stopCurrentMedia()
        val surface = glInputSurface ?: return
        val camManager = getApplication<Application>().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = camManager.cameraIdList.firstOrNull() ?: run {
            _errorMessage.value = "Tidak ada kamera fisik yang terdeteksi."
            return
        }

        _sourceType.value = SourceType.CAMERA
        _isPlaying.value = true
        cameraThread = HandlerThread("AlxCameraThread").apply { start() }
        val handler = Handler(cameraThread!!.looper)

        try {
            camManager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                    }
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            cameraSession = session
                            session.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: android.hardware.camera2.CaptureRequest,
                                    result: android.hardware.camera2.TotalCaptureResult
                                ) {
                                    requestRenderCallback?.invoke()
                                }
                            }, handler)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            _errorMessage.value = "Konfigurasi Camera2 gagal."
                        }
                    }, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    _errorMessage.value = "Camera2 Error code: $error"
                }
            }, handler)

            _metadata.value = MediaMetadataInfo(
                displayName = "Hardware Camera ($camId)",
                mimeType = "camera2/surface",
                width = 1280,
                height = 720,
                fps = 30f,
                codecName = "Camera2 Hardware Pipeline",
                isHardwareAccelerated = true,
                colorFormat = "OES_External",
                decoderStatus = "LIVE"
            )
        } catch (e: Exception) {
            _errorMessage.value = "Gagal membuka kamera: ${e.localizedMessage}"
        }
    }

    fun toggleMicrophone(enabled: Boolean) {
        _micEnabled.value = enabled
        if (enabled) {
            audioEngine.startMicrophoneCapture(viewModelScope)
        } else {
            audioEngine.stopMicrophoneCapture()
        }
    }

    fun updateTransform(updater: (TransformState) -> TransformState) {
        val updated = updater(_transform.value)
        _transform.value = updated
        requestRenderCallback?.invoke()
        viewModelScope.launch { settingsRepo.saveTransform(updated) }
    }

    fun togglePlayPause() {
        val next = !_isPlaying.value
        _isPlaying.value = next
        videoDecoder.isPaused = !next
        audioDecoder.isPaused = !next
    }

    fun restartPlayback() {
        videoDecoder.restart()
        audioDecoder.seekToMs(0L)
        _currentPositionMs.value = 0L
    }

    fun seekTo(ms: Long) {
        videoDecoder.seekToMs(ms)
        audioDecoder.seekToMs(ms)
        _currentPositionMs.value = ms
    }

    fun setLooping(loop: Boolean) {
        _isLooping.value = loop
        videoDecoder.isLooping = loop
        audioDecoder.isLooping = loop
        persistSettings()
    }

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        videoDecoder.playbackSpeed = speed
        audioEngine.playbackSpeed = speed
        persistSettings()
    }

    fun setVolume(vol: Float) {
        _volume.value = vol
        audioEngine.volume = vol
        persistSettings()
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        audioEngine.isMuted = muted
        persistSettings()
    }

    fun setOutputPreset(height: Int, fps: Int) {
        _outputResHeight.value = height
        _targetFps.value = fps
        persistSettings()
    }

    fun toggleOverlay() {
        _showOverlay.value = !_showOverlay.value
    }

    fun toggleOutput() {
        if (_isOutputRunning.value) {
            videoOutput.stop()
            _isOutputRunning.value = false
        } else {
            videoOutput.start()
            _isOutputRunning.value = true
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun stopCurrentMedia() {
        _isPlaying.value = false
        photoRefreshJob?.cancel()
        videoDecoder.stop()
        audioDecoder.stop()
        runCatching { cameraSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { cameraThread?.quitSafely() }
        cameraSession = null
        cameraDevice = null
    }

    private fun persistSettings(uriStr: String? = null) {
        viewModelScope.launch {
            settingsRepo.savePlaybackAndAudio(
                uri = uriStr,
                outputRes = _outputResHeight.value,
                fps = _targetFps.value,
                volume = _volume.value,
                muted = _isMuted.value,
                loop = _isLooping.value,
                speed = _playbackSpeed.value
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopCurrentMedia()
        videoOutput.stop()
        audioEngine.releaseAll()
    }
}
