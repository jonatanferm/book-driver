package se.jferm.bookdriver.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mixes a constant noise floor (about -60 dBFS, far below road noise) into the output while
 * [enabled] says so.
 *
 * Some car stereos and some phones' Bluetooth stacks treat near-silence as "nothing playing":
 * they mute or suspend the stream and take a moment to pick it up again. Music is never quiet
 * long enough to trigger it, but narration is full of quiet gaps between words, so speech cuts
 * out every few seconds (Audible has the same problem). With a noise floor the stream never
 * looks silent. This runs before speed and skip-silence processing and stays well under the
 * skip-silence threshold, so neither is affected.
 */
@OptIn(UnstableApi::class)
class KeepAliveNoise(private val enabled: () -> Boolean) : BaseAudioProcessor() {

    private var seed = 0x2545F491

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET // leave any other encoding untouched
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        when {
            !enabled() -> out.put(src)
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT -> {
                while (src.remaining() >= 2) {
                    val s = src.short + noise()
                    out.putShort(s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
                if (src.hasRemaining()) out.put(src)
            }
            else -> {
                while (src.remaining() >= 4) out.putFloat((src.float + noise() / 32768f).coerceIn(-1f, 1f))
                if (src.hasRemaining()) out.put(src)
            }
        }
        out.flip()
    }

    /** Triangular noise in 16-bit units, peak ±[PEAK], RMS ≈ PEAK/√6 (≈ 33, about -60 dBFS). */
    private fun noise(): Int = rand() - rand()

    private fun rand(): Int {
        var x = seed // xorshift32
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        seed = x
        return (x ushr 16) % (PEAK + 1)
    }

    companion object {
        const val PEAK = 80
    }
}
