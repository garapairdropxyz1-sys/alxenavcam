package com.alxenavcam.decoder

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

data class SelectedCodecResult(
    val codecName: String,
    val isHardware: Boolean,
    val colorFormatName: String
)

data class VirtualCamInvestigationReport(
    val canCreateSystemVirtualCamera: Boolean = false,
    val detectedPhysicalCameras: List<String>,
    val explanation: String
)

object DecoderCapabilities {

    fun findBestDecoder(format: MediaFormat, preferHardware: Boolean = true): SelectedCodecResult? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val candidates = codecList.codecInfos.filter {
            !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) }
        }

        if (candidates.isEmpty()) return null

        val sorted = candidates.sortedByDescending { info ->
            val isHw = isHardwareCodec(info)
            if (preferHardware) isHw else !isHw
        }

        val chosen = sorted.first()
        val isHw = isHardwareCodec(chosen)
        val caps = chosen.getCapabilitiesForType(mime)
        val hasSurfaceFormat = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        val colorName = if (hasSurfaceFormat) "COLOR_FormatSurface (Zero-Copy GPU)" else "YUV420_Flexible"

        return SelectedCodecResult(
            codecName = chosen.name,
            isHardware = isHw,
            colorFormatName = colorName
        )
    }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains(".sw.")
        }
    }

    fun inspectSystemCameraCapabilities(context: Context): VirtualCamInvestigationReport {
        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cams = camManager.cameraIdList.map { id ->
            val chars = camManager.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_BACK -> "Back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "External/UVC"
                else -> "Unknown"
            }
            "Camera ID $id ($facing)"
        }
        return VirtualCamInvestigationReport(
            canCreateSystemVirtualCamera = false,
            detectedPhysicalCameras = cams,
            explanation = "Tidak tersedia untuk aplikasi Android non-root biasa. CameraManager dan ICameraProvider HAL dilindungi SELinux dan hanya menerima registrasi dari system/vendor process. Output disediakan melalui Local MJPEG HTTP Stream & Clean Preview."
        )
    }
}
