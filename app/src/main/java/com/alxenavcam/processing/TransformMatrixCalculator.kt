package com.alxenavcam.processing

import android.opengl.Matrix
import kotlin.math.max
import kotlin.math.min

object TransformMatrixCalculator {

    fun computeMatrix(
        outMatrix: FloatArray,
        state: TransformState,
        sourceWidth: Int,
        sourceHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        Matrix.setIdentityM(outMatrix, 0)

        val isRotated90or270 = (state.rotationDegrees % 180 != 0)
        val effSrcW = if (isRotated90or270) sourceHeight.toFloat() else sourceWidth.toFloat()
        val effSrcH = if (isRotated90or270) sourceWidth.toFloat() else sourceHeight.toFloat()

        val srcRatio = if (effSrcH > 0f) effSrcW / effSrcH else 1f
        val dstRatio = state.effectiveTargetRatio()

        var scaleX = 1f
        var scaleY = 1f

        when (state.scalingMode) {
            ScalingMode.STRETCH -> {
                scaleX = 1f
                scaleY = 1f
            }
            ScalingMode.FIT -> {
                if (srcRatio > dstRatio) {
                    scaleX = 1f
                    scaleY = dstRatio / srcRatio
                } else {
                    scaleX = srcRatio / dstRatio
                    scaleY = 1f
                }
            }
            ScalingMode.FILL, ScalingMode.CROP -> {
                if (srcRatio > dstRatio) {
                    scaleX = srcRatio / dstRatio
                    scaleY = 1f
                } else {
                    scaleX = 1f
                    scaleY = dstRatio / srcRatio
                }
                if (state.scalingMode == ScalingMode.CROP) {
                    scaleX *= 1.08f
                    scaleY *= 1.08f
                }
            }
        }

        val vpRatio = if (viewportHeight > 0) viewportWidth.toFloat() / viewportHeight.toFloat() else dstRatio
        val viewportAdjustX: Float
        val viewportAdjustY: Float
        if (vpRatio > dstRatio) {
            viewportAdjustX = dstRatio / vpRatio
            viewportAdjustY = 1f
        } else {
            viewportAdjustX = 1f
            viewportAdjustY = vpRatio / dstRatio
        }

        Matrix.translateM(outMatrix, 0, state.panX, state.panY, 0f)

        val clampedZoom = min(max(state.zoom, 1.0f), state.maxZoom)
        val flipSignX = if (state.flipHorizontal) -1f else 1f
        val flipSignY = if (state.flipVertical) -1f else 1f

        val finalScaleX = scaleX * viewportAdjustX * clampedZoom * flipSignX
        val finalScaleY = scaleY * viewportAdjustY * clampedZoom * flipSignY
        Matrix.scaleM(outMatrix, 0, finalScaleX, finalScaleY, 1f)

        Matrix.rotateM(outMatrix, 0, -state.rotationDegrees.toFloat(), 0f, 0f, 1f)
    }
}
