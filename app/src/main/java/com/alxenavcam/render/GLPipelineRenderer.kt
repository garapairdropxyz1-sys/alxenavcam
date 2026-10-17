package com.alxenavcam.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.view.Surface
import com.alxenavcam.processing.TransformMatrixCalculator
import com.alxenavcam.processing.TransformState
import com.alxenavcam.util.AppLogger
import com.alxenavcam.util.LogCategory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLPipelineRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val onFrameMetrics: (Int, Float) -> Unit,
    private val onFrameBytesAvailable: ((ByteBuffer, Int, Int) -> Unit)? = null
) : GLSurfaceView.Renderer {

    private val vertexCoords = floatArrayOf(
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    )
    private val textureCoords = floatArrayOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertexCoords); position(0) }
    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(textureCoords); position(0) }

    private var programOes = 0
    private var program2D = 0
    private var oesTextureId = 0
    private var photoTextureId = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private val frameAvailable = AtomicBoolean(false)

    @Volatile var transformState: TransformState = TransformState()
    @Volatile var isPhotoMode: Boolean = false
    @Volatile var sourceWidth: Int = 1280
    @Volatile var sourceHeight: Int = 720
    @Volatile var enableReadbackForOutput: Boolean = false

    private var pendingPhotoBitmap: Bitmap? = null
    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private var viewportW = 720
    private var viewportH = 1280

    private var frameCount = 0
    private var lastFpsTimestampMs = SystemClock.elapsedRealtime()
    private var readbackBuffer: ByteBuffer? = null

    fun setPhotoBitmap(bitmap: Bitmap) {
        synchronized(this) {
            pendingPhotoBitmap = bitmap
            sourceWidth = bitmap.width
            sourceHeight = bitmap.height
            isPhotoMode = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.07f, 1.0f)
        programOes = createProgram(Shaders.VERTEX_SHADER, Shaders.FRAGMENT_SHADER_OES)
        program2D = createProgram(Shaders.VERTEX_SHADER, Shaders.FRAGMENT_SHADER_2D)

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        oesTextureId = textures[0]
        photoTextureId = textures[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener { frameAvailable.set(true) }
        }
        decoderSurface = Surface(surfaceTexture)
        decoderSurface?.let { onSurfaceReady(it) }

        Matrix.setIdentityM(texMatrix, 0)
        AppLogger.log(LogCategory.RENDER, "OpenGL ES 2.0 Pipeline siap.")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportW, viewportH)
    }

    override fun onDrawFrame(gl: GL10?) {
        val startRenderNs = SystemClock.elapsedRealtimeNanos()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        synchronized(this) {
            pendingPhotoBitmap?.let { bmp ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                pendingPhotoBitmap = null
            }
        }

        if (!isPhotoMode && frameAvailable.compareAndSet(true, false)) {
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(texMatrix)
        } else if (isPhotoMode) {
            Matrix.setIdentityM(texMatrix, 0)
            Matrix.translateM(texMatrix, 0, 0f, 1f, 0f)
            Matrix.scaleM(texMatrix, 0, 1f, -1f, 1f)
        }

        TransformMatrixCalculator.computeMatrix(
            outMatrix = mvpMatrix,
            state = transformState,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            viewportWidth = viewportW,
            viewportHeight = viewportH
        )

        val activeProgram = if (isPhotoMode) program2D else programOes
        GLES20.glUseProgram(activeProgram)

        val aPositionLoc = GLES20.glGetAttribLocation(activeProgram, "aPosition")
        val aTexCoordLoc = GLES20.glGetAttribLocation(activeProgram, "aTextureCoord")
        val uMvpLoc = GLES20.glGetUniformLocation(activeProgram, "uMVPMatrix")
        val uTexMatLoc = GLES20.glGetUniformLocation(activeProgram, "uTexMatrix")

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatLoc, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (isPhotoMode) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTextureId)
        } else {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        if (enableReadbackForOutput && onFrameBytesAvailable != null) {
            val capacity = viewportW * viewportH * 4
            var buf = readbackBuffer
            if (buf == null || buf.capacity() != capacity) {
                buf = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
                readbackBuffer = buf
            }
            val safeBuf = buf
            if (safeBuf != null) {
                safeBuf.position(0)
                GLES20.glReadPixels(0, 0, viewportW, viewportH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, safeBuf)
                onFrameBytesAvailable.invoke(safeBuf, viewportW, viewportH)
            }
        }

        val renderTimeMs = (SystemClock.elapsedRealtimeNanos() - startRenderNs) / 1_000_000f
        frameCount++
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastFpsTimestampMs >= 1000L) {
            val currentFps = frameCount
            frameCount = 0
            lastFpsTimestampMs = nowMs
            onFrameMetrics(currentFps, renderTimeMs)
        }
    }

    fun release() {
        decoderSurface?.release()
        surfaceTexture?.release()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
