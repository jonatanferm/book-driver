package se.jferm.bookdriver.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.jferm.bookdriver.DiagLog
import se.jferm.bookdriver.data.Settings
import kotlin.math.sqrt

/**
 * Sleep timer. Counts down only while audio is playing, fades the volume out over the
 * last 10 seconds, and (optionally) a shake during the last minute adds 10 minutes.
 * "End of chapter" pauses at the next chapter boundary.
 */
class SleepTimer(context: Context, private val settings: Settings) {

    enum class Mode { OFF, MINUTES, END_OF_CHAPTER }

    data class State(val mode: Mode = Mode.OFF, val remainingMs: Long = 0, val chosenMinutes: Int = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val sensors = context.getSystemService(SensorManager::class.java)
    private var player: BookPlayer? = null
    private var job: Job? = null
    private var shakeRegistered = false

    fun setMinutes(minutes: Int) {
        _state.value = State(Mode.MINUTES, minutes * 60_000L, minutes)
        DiagLog.i(TAG, "set $minutes min")
    }

    fun setEndOfChapter() {
        _state.value = State(Mode.END_OF_CHAPTER, 0)
        DiagLog.i(TAG, "set end of chapter")
    }

    fun cancel() {
        _state.value = State()
        player?.exo?.volume = 1f
        unregisterShake()
    }

    /** Called by the playback service while it runs. */
    fun attach(player: BookPlayer, scope: CoroutineScope) {
        this.player = player
        job?.cancel()
        job = scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - last
                last = now
                tick(player, elapsed)
            }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        unregisterShake()
        player = null
    }

    private fun tick(p: BookPlayer, elapsedMs: Long) {
        val s = _state.value
        if (s.mode == Mode.OFF) return
        val remaining = when (s.mode) {
            Mode.MINUTES -> if (p.isPlaying) s.remainingMs - elapsedMs else s.remainingMs
            Mode.END_OF_CHAPTER -> {
                val book = p.currentBook() ?: return
                val idx = book.chapterIndexAt(p.currentMediaItemIndex, p.currentPosition)
                val left = book.chapterEndGlobal(idx) - p.globalPosition()
                (left / p.playbackParameters.speed).toLong()
            }
            Mode.OFF -> 0
        }
        _state.value = s.copy(remainingMs = remaining.coerceAtLeast(0))
        if (!p.isPlaying) return

        p.exo.volume = if (remaining < FADE_MS) (remaining.toFloat() / FADE_MS).coerceIn(0f, 1f) else 1f

        if (remaining < 60_000 && settings.shakeToExtend.value) registerShake() else unregisterShake()

        if (remaining <= 0) {
            DiagLog.i(TAG, "time's up, pausing")
            p.pause()
            p.exo.volume = 1f
            _state.value = State()
            unregisterShake()
        }
    }

    private val shakeListener = object : SensorEventListener {
        private var lastShake = 0L
        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
            val now = SystemClock.elapsedRealtime()
            if (g > SHAKE_G && now - lastShake > 1_500) {
                lastShake = now
                val s = _state.value
                if (s.mode == Mode.OFF) return
                _state.value = State(Mode.MINUTES, s.remainingMs.coerceAtLeast(0) + EXTEND_MS, s.chosenMinutes.coerceAtLeast(10))
                player?.exo?.volume = 1f
                DiagLog.i(TAG, "shake: +10 min")
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun registerShake() {
        if (shakeRegistered) return
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        shakeRegistered = sensors.registerListener(shakeListener, accel, SensorManager.SENSOR_DELAY_UI)
    }

    private fun unregisterShake() {
        if (!shakeRegistered) return
        sensors?.unregisterListener(shakeListener)
        shakeRegistered = false
    }

    companion object {
        private const val TAG = "sleep"
        private const val TICK_MS = 500L
        private const val FADE_MS = 10_000L
        private const val EXTEND_MS = 10 * 60_000L
        private const val SHAKE_G = 2.2f
        val OPTIONS_MINUTES = listOf(15, 30, 45, 60)
    }
}
