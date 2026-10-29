package se.jferm.bookdriver.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

class KeepAliveNoiseTest {
    private val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    private fun run(enabled: Boolean, samples: ShortArray): ShortArray {
        val p = KeepAliveNoise { enabled }
        p.configure(format); p.flush()
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }; input.flip()
        p.queueInput(input)
        assertEquals("input fully consumed", 0, input.remaining())
        val out = p.output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test fun silenceBecomesAFaintNoiseFloor() {
        val out = run(true, ShortArray(88_200))
        val rms = sqrt(out.sumOf { it.toDouble() * it } / out.size)
        val dbfs = 20 * log10(rms / 32768)
        println("noise floor: %.1f dBFS RMS, peak %d".format(dbfs, out.maxOf { kotlin.math.abs(it.toInt()) }))
        assertTrue("not silent", out.count { it.toInt() != 0 } > out.size * 0.9)
        assertTrue("about -60 dBFS (was $dbfs)", dbfs in -63.0..-57.0)
        assertTrue("never exceeds the peak", out.all { kotlin.math.abs(it.toInt()) <= KeepAliveNoise.PEAK })
    }

    @Test fun offIsAnExactPassThrough() {
        val samples = ShortArray(1000) { (it * 37 - 18_000).toShort() }
        assertArrayEquals(samples, run(false, samples))
    }

    @Test fun loudSamplesClampInsteadOfWrapping() {
        val out = run(true, ShortArray(1000) { Short.MAX_VALUE })
        assertTrue(out.all { it > 32_000 })
    }
}
