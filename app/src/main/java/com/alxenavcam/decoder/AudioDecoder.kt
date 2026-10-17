package com.alxenavcam.decoder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.alxenavcam.util.AppLogger
import com.alxenavcam.util.LogCategory
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class AudioDecoder(
    private val context: Context,
    private val onPcmData: (ByteArray, Int, Int, Int) -> Unit
) {
    private var audioJob: Job? = null
    @Volatile var isPaused: Boolean = false
    @Volatile var isLooping: Boolean = true
    @Volatile private var seekTargetUs: Long = -1L

    fun start(uri: Uri, scope: CoroutineScope) {
        stop()
        audioJob = scope.launch(Dispatchers.Default) {
            decodeAudioLoop(uri)
        }
    }

    fun seekToMs(ms: Long) {
        seekTargetUs = ms * 1000L
    }

    fun stop() {
        audioJob?.cancel()
        audioJob = null
    }

    private suspend fun CoroutineScope.decodeAudioLoop(uri: Uri) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val tf = extractor.getTrackFormat(i)
                val mime = tf.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = tf
                    break
                }
            }
            if (audioTrack == -1 || format == null) {
                AppLogger.log(LogCategory.AUDIO, "Media tidak memiliki track audio.")
                return
            }

            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isEOS = false
            var tempArray = ByteArray(8192)

            while (isActive) {
                if (isPaused) {
                    delay(20)
                    continue
                }
                val seekUs = seekTargetUs
                if (seekUs >= 0L) {
                    seekTargetUs = -1L
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    isEOS = false
                }

                if (!isEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf: ByteBuffer? = codec.getInputBuffer(inIdx)
                        if (buf != null) {
                            val read = extractor.readSampleData(buf, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEOS = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        if (tempArray.size < info.size) {
                            tempArray = ByteArray(info.size)
                        }
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.get(tempArray, 0, info.size)
                        onPcmData(tempArray, info.size, sampleRate, channels)
                    }
                    codec.releaseOutputBuffer(outIdx, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (isLooping) {
                            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            codec.flush()
                            isEOS = false
                        } else {
                            isPaused = true
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(LogCategory.ERROR, "AudioDecoder error", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }
}
