package com.alxenavcam.decoder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import com.alxenavcam.data.media.MediaMetadataInfo
import com.alxenavcam.util.AppLogger
import com.alxenavcam.util.LogCategory
import kotlinx.coroutines.*
import kotlin.math.roundToLong

class VideoDecoder(
    private val context: Context,
    private val onMetadataReady: (MediaMetadataInfo) -> Unit,
    private val onProgressUpdate: (Long, Long) -> Unit,
    private val onDecodeStat: (Float, Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private var decoderJob: Job? = null
    @Volatile var isPaused: Boolean = false
    @Volatile var isLooping: Boolean = true
    @Volatile var playbackSpeed: Float = 1.0f
    @Volatile private var pendingSeekUs: Long = -1L

    fun start(uri: Uri, outputSurface: Surface, scope: CoroutineScope) {
        stop()
        decoderJob = scope.launch(Dispatchers.Default) {
            runDecodeLoop(uri, outputSurface)
        }
    }

    fun seekToMs(positionMs: Long) {
        pendingSeekUs = positionMs * 1000L
    }

    fun restart() {
        pendingSeekUs = 0L
    }

    fun stop() {
        decoderJob?.cancel()
        decoderJob = null
    }

    private suspend fun CoroutineScope.runDecodeLoop(uri: Uri, surface: Surface) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            var videoTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (videoTrackIndex == -1 || format == null) {
                onError("File tidak memiliki track video yang valid.")
                return
            }

            extractor.selectTrack(videoTrackIndex)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() else 30f
            val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else 0
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"

            var selected = DecoderCapabilities.findBestDecoder(format, preferHardware = true)
            if (selected == null) {
                onError("Codec $mime tidak didukung di perangkat ini.")
                return
            }

            try {
                codec = MediaCodec.createByCodecName(selected.codecName)
                codec.configure(format, surface, null, 0)
                codec.start()
            } catch (hwEx: Exception) {
                AppLogger.log(LogCategory.DECODER, "Hardware decoder gagal, mencoba fallback software decoder...", hwEx)
                codec?.release()
                selected = DecoderCapabilities.findBestDecoder(format, preferHardware = false)
                if (selected == null) throw hwEx
                codec = MediaCodec.createByCodecName(selected.codecName)
                codec.configure(format, surface, null, 0)
                codec.start()
            }

            AppLogger.log(
                LogCategory.DECODER,
                "Decoder aktif: ${selected.codecName} (HW=${selected.isHardware}) ${width}x${height} @${fps}fps"
            )

            onMetadataReady(
                MediaMetadataInfo(
                    uri = uri,
                    displayName = uri.lastPathSegment ?: "Video",
                    mimeType = mime,
                    width = width,
                    height = height,
                    fps = fps,
                    durationMs = durationUs / 1000L,
                    bitrate = bitrate,
                    codecName = selected.codecName,
                    isHardwareAccelerated = selected.isHardware,
                    colorFormat = selected.colorFormatName,
                    decoderStatus = "RUNNING"
                )
            )

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var startWallClockNs = System.nanoTime()
            var basePtsUs = -1L

            while (isActive) {
                if (isPaused) {
                    delay(16)
                    startWallClockNs = System.nanoTime()
                    basePtsUs = -1L
                    continue
                }

                val seekTarget = pendingSeekUs
                if (seekTarget >= 0L) {
                    pendingSeekUs = -1L
                    extractor.seekTo(seekTarget, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    isInputEOS = false
                    basePtsUs = -1L
                    startWallClockNs = System.nanoTime()
                }

                val decodeStartNs = SystemClock.elapsedRealtimeNanos()

                if (!isInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                val sampleTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStartNs) / 1_000_000f
                    val ptsUs = bufferInfo.presentationTimeUs

                    if (basePtsUs < 0L) {
                        basePtsUs = ptsUs
                        startWallClockNs = System.nanoTime()
                    }

                    val speed = playbackSpeed.coerceIn(0.25f, 2.0f)
                    val targetElapsedNs = (((ptsUs - basePtsUs) * 1000L) / speed).roundToLong()
                    val actualElapsedNs = System.nanoTime() - startWallClockNs
                    val waitNs = targetElapsedNs - actualElapsedNs

                    var dropped = false
                    if (waitNs > 2_000_000L) {
                        delay(waitNs / 1_000_000L)
                    } else if (waitNs < -45_000_000L) {
                        dropped = true
                    }

                    codec.releaseOutputBuffer(outIndex, !dropped)
                    onDecodeStat(decodeMs, dropped)
                    onProgressUpdate(ptsUs / 1000L, durationUs / 1000L)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (isLooping) {
                            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            codec.flush()
                            isInputEOS = false
                            basePtsUs = -1L
                        } else {
                            isPaused = true
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(LogCategory.ERROR, "VideoDecoder error", e)
            onError("Decoder Error: ${e.localizedMessage ?: "Unknown codec failure"}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            AppLogger.log(LogCategory.DECODER, "Decoder telah di-release dengan aman.")
        }
    }
}
