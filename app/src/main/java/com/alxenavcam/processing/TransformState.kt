package com.alxenavcam.processing

enum class AspectRatioPreset(val label: String, val ratioValue: Float?) {
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_3_4("3:4", 3f / 4f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_1_1("1:1", 1f),
    CUSTOM("Custom", null)
}

enum class ScalingMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    CROP("Crop"),
    STRETCH("Stretch")
}

data class TransformState(
    val aspectRatio: AspectRatioPreset = AspectRatioPreset.RATIO_9_16,
    val customAspectW: Float = 9f,
    val customAspectH: Float = 16f,
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val zoom: Float = 1.0f,
    val maxZoom: Float = 5.0f,
    val panX: Float = 0.0f,
    val panY: Float = 0.0f,
    val scalingMode: ScalingMode = ScalingMode.FIT
) {
    fun effectiveTargetRatio(): Float {
        return aspectRatio.ratioValue ?: (customAspectW / customAspectH.coerceAtLeast(1f))
    }
}
