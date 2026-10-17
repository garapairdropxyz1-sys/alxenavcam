package com.alxenavcam.data.media

import android.graphics.Bitmap
import android.net.Uri

enum class SourceType {
    NONE, VIDEO, PHOTO, CAMERA
}

data class MediaMetadataInfo(
    val uri: Uri? = null,
    val displayName: String = "No Media Selected",
    val mimeType: String = "-",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val durationMs: Long = 0L,
    val bitrate: Int = 0,
    val codecName: String = "-",
    val isHardwareAccelerated: Boolean = false,
    val colorFormat: String = "-",
    val decoderStatus: String = "IDLE",
    val thumbnail: Bitmap? = null
)

data class PerformanceMetrics(
    val currentFps: Int = 0,
    val droppedFrames: Int = 0,
    val decodeTimeMs: Float = 0f,
    val renderTimeMs: Float = 0f,
    val audioBufferBytes: Int = 0,
    val memoryUsageMb: Long = 0L,
    val lowFpsWarning: Boolean = false
)
