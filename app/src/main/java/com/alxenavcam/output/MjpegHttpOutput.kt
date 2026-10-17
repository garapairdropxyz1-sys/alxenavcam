package com.alxenavcam.output

import android.graphics.Bitmap
import com.alxenavcam.util.AppLogger
import com.alxenavcam.util.LogCategory
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

class MjpegHttpOutput(private val port: Int = 8080) : VideoOutput {

    private val boundedQueue = ArrayBlockingQueue<ByteArray>(2)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false
    private var reusableBitmap: Bitmap? = null

    override fun start() {
        if (isRunning) return
        isRunning = true
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(port)
                AppLogger.log(LogCategory.OUTPUT, "MJPEG Server berjalan di http://127.0.0.1:$port/stream.mjpeg")
                while (isActive && isRunning) {
                    val client = serverSocket?.accept() ?: break
                    launch {
                        handleClient(client.getOutputStream())
                        runCatching { client.close() }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    AppLogger.log(LogCategory.ERROR, "MJPEG Server gagal berjalan", e)
                }
            }
        }
    }

    private fun handleClient(outputStream: OutputStream) {
        try {
            val header = "HTTP/1.0 200 OK\r\n" +
                    "Connection: close\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=--alxenavframe\r\n\r\n"
            outputStream.write(header.toByteArray())
            outputStream.flush()

            while (isRunning) {
                val jpegBytes = boundedQueue.poll()
                if (jpegBytes != null) {
                    val frameHeader = "--alxenavframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegBytes.size}\r\n\r\n"
                    outputStream.write(frameHeader.toByteArray())
                    outputStream.write(jpegBytes)
                    outputStream.write("\r\n".toByteArray())
                    outputStream.flush()
                } else {
                    Thread.sleep(15)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun submitFrame(rgbaBuffer: ByteBuffer, width: Int, height: Int, timestampNs: Long) {
        if (!isRunning || boundedQueue.remainingCapacity() == 0) return

        var bmp = reusableBitmap
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            reusableBitmap = bmp
        }
        rgbaBuffer.position(0)
        bmp.copyPixelsFromBuffer(rgbaBuffer)

        val baos = ByteArrayOutputStream(32_768)
        bmp.compress(Bitmap.CompressFormat.JPEG, 78, baos)
        boundedQueue.offer(baos.toByteArray())
    }

    override fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverJob?.cancel()
        boundedQueue.clear()
        AppLogger.log(LogCategory.OUTPUT, "MJPEG Server dihentikan.")
    }

    override fun getCapabilities(): OutputCapabilities {
        return OutputCapabilities(
            backendName = "Local MJPEG Stream + Clean Cast Mode",
            supportsSystemWideCameraReplacement = false,
            description = "Menghasilkan stream realtime ke http://127.0.0.1:$port/stream.mjpeg untuk Browser / OBS & Clean Screen Capture.",
            endpointOrPath = "http://127.0.0.1:$port/stream.mjpeg"
        )
    }
}
