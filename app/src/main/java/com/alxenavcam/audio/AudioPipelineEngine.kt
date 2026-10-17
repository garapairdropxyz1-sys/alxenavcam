package com.alxenavcam.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import com.alxenavcam.output.AudioOutput
import com.alxenavcam.util.AppLogger
import com.alxenavcam.util.LogCategory
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

class AudioPipelineEngine(
    private val onAudioLevelUpdate: (Float, Int) -> Unit
) {
    @Volatile var volume: Float = 1.0f
    @Volatile var isMuted: Boolean = false
    @Volatile var playbackSpeed: Float = 1.0f
    @Volatile var mixMicrophone: Boolean = false

    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 0
    private var currentChannels = 0

    private var micJob: Job? = null
    private val latestMicPcm = ShortArray(4096)
    @Volatile private var hasMicData = false

    var externalAudioOutput: AudioOutput? = null

    @SuppressLint("MissingPermission")
    fun startMicrophoneCapture(scope: CoroutineScope) {
        stopMicrophoneCapture()
        mixMicrophone = true
        micJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    AppLogger.log(LogCategory.ERROR, "Gagal menginisialisasi Microphone AudioRecord.")
                    return@launch
                }
                recorder.startRecording()
                AppLogger.log(LogCategory.AUDIO, "Microphone capture aktif @ 44.1kHz PCM16.")

                val readBuf = ShortArray(1024)
                val byteOut = ByteArray(2048)
                while (isActive && mixMicrophone) {
                    val read = recorder.read(readBuf, 0, readBuf.size)
                    if (read > 0) {
                        synchronized(latestMicPcm) {
                            System.arraycopy(readBuf, 0, latestMicPcm, 0, read.coerceAtMost(latestMicPcm.size))
                            hasMicData = true
                        }
                        if (audioTrack == null) {
                            for (i in 0 until read) {
                                val s = (readBuf[i] * (if (isMuted) 0f else volume)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                byteOut[i * 2] = (s.toInt() and 0xFF).toByte()
                                byteOut[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                            }
                            submitPcmFrame(byteOut, read * 2, sampleRate, 1, fromMicDirect = true)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(LogCategory.ERROR, "Microphone loop error", e)
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                hasMicData = false
            }
        }
    }

    fun stopMicrophoneCapture() {
        mixMicrophone = false
        micJob?.cancel()
        micJob = null
        hasMicData = false
    }

    fun submitPcmFrame(
        pcmBytes: ByteArray,
        size: Int,
        sampleRate: Int,
        channels: Int,
        fromMicDirect: Boolean = false
    ) {
        ensureAudioTrack(sampleRate, channels)
        val track = audioTrack ?: return

        val gain = if (isMuted) 0f else volume.coerceIn(0f, 2f)
        val processed = ByteArray(size)
        var sumSquares = 0.0
        val sampleCount = size / 2

        synchronized(latestMicPcm) {
            for (i in 0 until sampleCount) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                var sample = ((hi shl 8) or lo).toShort().toInt()

                if (!fromMicDirect && mixMicrophone && hasMicData && i < latestMicPcm.size) {
                    sample += latestMicPcm[i].toInt()
                }

                val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                processed[i * 2] = (scaled and 0xFF).toByte()
                processed[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()

                val norm = abs(scaled) / 32768.0
                sumSquares += norm * norm
            }
        }

        val rms = if (sampleCount > 0) sqrt(sumSquares / sampleCount).toFloat().coerceIn(0f, 1f) else 0f
        onAudioLevelUpdate(rms, size)

        runCatching {
            track.write(processed, 0, size)
            externalAudioOutput?.submitAudio(processed, size, sampleRate, channels)
        }
    }

    private fun ensureAudioTrack(sampleRate: Int, channels: Int) {
        if (audioTrack != null && currentSampleRate == sampleRate && currentChannels == channels) {
            updatePlaybackSpeedInternal()
            return
        }
        releaseTrack()
        currentSampleRate = sampleRate
        currentChannels = channels

        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }

        updatePlaybackSpeedInternal()
    }

    private fun updatePlaybackSpeedInternal() {
        val track = audioTrack ?: return
        runCatching {
            val params = PlaybackParams().setSpeed(playbackSpeed.coerceIn(0.25f, 2.0f)).setPitch(1.0f)
            track.playbackParams = params
        }
    }

    fun releaseTrack() {
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }

    fun releaseAll() {
        stopMicrophoneCapture()
        releaseTrack()
    }
}
