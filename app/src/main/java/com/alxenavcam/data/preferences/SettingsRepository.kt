package com.alxenavcam.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.alxenavcam.processing.AspectRatioPreset
import com.alxenavcam.processing.ScalingMode
import com.alxenavcam.processing.TransformState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "alxenavcam_settings")

data class PersistedSettings(
    val lastMediaUri: String = "",
    val transformState: TransformState = TransformState(),
    val outputHeightPreset: Int = 720,
    val targetFps: Int = 30,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val isLooping: Boolean = true,
    val playbackSpeed: Float = 1.0f
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val LAST_URI = stringPreferencesKey("last_media_uri")
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        val ROTATION = intPreferencesKey("rotation_deg")
        val FLIP_H = booleanPreferencesKey("flip_h")
        val FLIP_V = booleanPreferencesKey("flip_v")
        val ZOOM = floatPreferencesKey("zoom")
        val PAN_X = floatPreferencesKey("pan_x")
        val PAN_Y = floatPreferencesKey("pan_y")
        val SCALING_MODE = stringPreferencesKey("scaling_mode")
        val OUTPUT_RES = intPreferencesKey("output_res")
        val TARGET_FPS = intPreferencesKey("target_fps")
        val VOLUME = floatPreferencesKey("volume")
        val MUTED = booleanPreferencesKey("muted")
        val LOOP = booleanPreferencesKey("loop")
        val SPEED = floatPreferencesKey("speed")
    }

    val settingsFlow: Flow<PersistedSettings> = context.dataStore.data.map { prefs ->
        PersistedSettings(
            lastMediaUri = prefs[Keys.LAST_URI] ?: "",
            transformState = TransformState(
                aspectRatio = runCatching {
                    AspectRatioPreset.valueOf(prefs[Keys.ASPECT_RATIO] ?: AspectRatioPreset.RATIO_9_16.name)
                }.getOrDefault(AspectRatioPreset.RATIO_9_16),
                rotationDegrees = prefs[Keys.ROTATION] ?: 0,
                flipHorizontal = prefs[Keys.FLIP_H] ?: false,
                flipVertical = prefs[Keys.FLIP_V] ?: false,
                zoom = prefs[Keys.ZOOM] ?: 1.0f,
                panX = prefs[Keys.PAN_X] ?: 0f,
                panY = prefs[Keys.PAN_Y] ?: 0f,
                scalingMode = runCatching {
                    ScalingMode.valueOf(prefs[Keys.SCALING_MODE] ?: ScalingMode.FIT.name)
                }.getOrDefault(ScalingMode.FIT)
            ),
            outputHeightPreset = prefs[Keys.OUTPUT_RES] ?: 720,
            targetFps = prefs[Keys.TARGET_FPS] ?: 30,
            volume = prefs[Keys.VOLUME] ?: 1.0f,
            isMuted = prefs[Keys.MUTED] ?: false,
            isLooping = prefs[Keys.LOOP] ?: true,
            playbackSpeed = prefs[Keys.SPEED] ?: 1.0f
        )
    }

    suspend fun saveTransform(state: TransformState) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ASPECT_RATIO] = state.aspectRatio.name
            prefs[Keys.ROTATION] = state.rotationDegrees
            prefs[Keys.FLIP_H] = state.flipHorizontal
            prefs[Keys.FLIP_V] = state.flipVertical
            prefs[Keys.ZOOM] = state.zoom
            prefs[Keys.PAN_X] = state.panX
            prefs[Keys.PAN_Y] = state.panY
            prefs[Keys.SCALING_MODE] = state.scalingMode.name
        }
    }

    suspend fun savePlaybackAndAudio(
        uri: String?,
        outputRes: Int,
        fps: Int,
        volume: Float,
        muted: Boolean,
        loop: Boolean,
        speed: Float
    ) {
        context.dataStore.edit { prefs ->
            if (uri != null) prefs[Keys.LAST_URI] = uri
            prefs[Keys.OUTPUT_RES] = outputRes
            prefs[Keys.TARGET_FPS] = fps
            prefs[Keys.VOLUME] = volume
            prefs[Keys.MUTED] = muted
            prefs[Keys.LOOP] = loop
            prefs[Keys.SPEED] = speed
        }
    }
}
