package com.alxenavcam

import com.alxenavcam.processing.AspectRatioPreset
import com.alxenavcam.processing.ScalingMode
import com.alxenavcam.processing.TransformMatrixCalculator
import com.alxenavcam.processing.TransformState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformMatrixTest {

    @Test
    fun `test effective target aspect ratio presets`() {
        val state916 = TransformState(aspectRatio = AspectRatioPreset.RATIO_9_16)
        assertEquals(9f / 16f, state916.effectiveTargetRatio(), 0.001f)

        val stateCustom = TransformState(
            aspectRatio = AspectRatioPreset.CUSTOM,
            customAspectW = 21f,
            customAspectH = 9f
        )
        assertEquals(21f / 9f, stateCustom.effectiveTargetRatio(), 0.001f)
    }

    @Test
    fun `test zoom clamping and flip signs in matrix calculation`() {
        val matrix = FloatArray(16)
        val state = TransformState(
            aspectRatio = AspectRatioPreset.RATIO_1_1,
            scalingMode = ScalingMode.STRETCH,
            zoom = 2.0f,
            flipHorizontal = true,
            flipVertical = false
        )

        TransformMatrixCalculator.computeMatrix(
            outMatrix = matrix,
            state = state,
            sourceWidth = 1080,
            sourceHeight = 1080,
            viewportWidth = 1080,
            viewportHeight = 1080
        )

        assertTrue("Scale X harus negatif saat Flip Horizontal aktif", matrix[0] < 0f)
        assertEquals(-2.0f, matrix[0], 0.01f)
        assertEquals(2.0f, matrix[5], 0.01f)
    }
}
