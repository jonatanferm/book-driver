package se.jferm.bookdriver.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Playback preferences, shared by the UI and the playback service (same process). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _speed = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1.0f))
    val speed: StateFlow<Float> = _speed

    private val _skipSilence = MutableStateFlow(prefs.getBoolean(KEY_SKIP_SILENCE, false))
    val skipSilence: StateFlow<Boolean> = _skipSilence

    private val _smartRewind = MutableStateFlow(prefs.getBoolean(KEY_SMART_REWIND, true))
    val smartRewind: StateFlow<Boolean> = _smartRewind

    /**
     * Off (default): when another app plays a short sound (navigation prompt, notification,
     * a car's own Bluetooth audio focus grab), keep reading at a lower volume. On: pause instead.
     * Pausing is Android's default for speech and causes 0.5–2 s dropouts in some cars.
     */
    private val _pauseForOtherSounds = MutableStateFlow(prefs.getBoolean(KEY_PAUSE_FOR_SOUNDS, false))
    val pauseForOtherSounds: StateFlow<Boolean> = _pauseForOtherSounds

    private val _shakeToExtend = MutableStateFlow(prefs.getBoolean(KEY_SHAKE, true))
    val shakeToExtend: StateFlow<Boolean> = _shakeToExtend

    fun setSpeed(value: Float) {
        val v = (Math.round(value.coerceIn(MIN_SPEED, MAX_SPEED) * 20) / 20f)
        _speed.value = v
        prefs.edit { putFloat(KEY_SPEED, v) }
    }

    fun setSkipSilence(v: Boolean) { _skipSilence.value = v; prefs.edit { putBoolean(KEY_SKIP_SILENCE, v) } }
    fun setSmartRewind(v: Boolean) { _smartRewind.value = v; prefs.edit { putBoolean(KEY_SMART_REWIND, v) } }
    fun setPauseForOtherSounds(v: Boolean) { _pauseForOtherSounds.value = v; prefs.edit { putBoolean(KEY_PAUSE_FOR_SOUNDS, v) } }
    fun setShakeToExtend(v: Boolean) { _shakeToExtend.value = v; prefs.edit { putBoolean(KEY_SHAKE, v) } }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        val SPEED_PRESETS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
        private const val KEY_SPEED = "speed"
        private const val KEY_SKIP_SILENCE = "skip_silence"
        private const val KEY_SMART_REWIND = "smart_rewind"
        private const val KEY_SHAKE = "shake_to_extend"
        private const val KEY_PAUSE_FOR_SOUNDS = "pause_for_other_sounds"
    }
}
