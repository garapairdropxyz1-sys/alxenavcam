package com.alxenavcam.output

import java.nio.ByteBuffer

data class OutputCapabilities(
    val backendName: String,
    val supportsSystemWideCameraReplacement: Boolean,
    val description: String,
    val endpointOrPath: String
)

interface VideoOutput {
    fun start()
    fun stop()
    fun submitFrame(rgbaBuffer: ByteBuffer, width: Int, height: Int, timestampNs: Long)
    fun getCapabilities(): OutputCapabilities
}

interface AudioOutput {
    fun start()
    fun stop()
    fun submitAudio(pcmData: ByteArray, size: Int, sampleRate: Int, channels: Int)
}
