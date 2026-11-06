package app.headsup.core.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessTest {
    private fun tone(freq: Double, amplitude: Double, seconds: Double, rate: Int = 48000, channels: Int = 2): ShortArray {
        val n = (seconds * rate).toInt()
        return ShortArray(n * channels) { i -> (amplitude * 32767 * sin(2 * PI * freq * (i / channels) / rate)).toInt().toShort() }
    }

    @Test
    fun aFullScaleToneIsAboutMinusThreeLufsPerChannel() {
        // EBU's reference: a 997 Hz sine at 0 dBFS in one channel measures −3.01 LUFS.
        val m = Loudness.Meter(48000, 1).apply { add(tone(997.0, 1.0, 5.0, channels = 1)) }.result()
        assertEquals(-3.01, Loudness.integrated(m.power), 0.2)
        assertEquals(1f, m.peak, 0.01f)
    }

    @Test
    fun twentyDecibelsQuieterIsTwentyLuLess() {
        val loud = Loudness.integrated(Loudness.Meter(44100, 2).apply { add(tone(1000.0, 0.5, 4.0, 44100)) }.result().power)
        val quiet = Loudness.integrated(Loudness.Meter(44100, 2).apply { add(tone(1000.0, 0.05, 4.0, 44100)) }.result().power)
        assertEquals(20.0, loud - quiet, 0.3)
    }

    @Test
    fun silenceIsGatedOut() {
        val m = Loudness.Meter(48000, 1)
        m.add(tone(1000.0, 0.5, 3.0, channels = 1))
        m.add(ShortArray(48000 * 6))
        val withPause = Loudness.integrated(m.result().power)
        val alone = Loudness.integrated(Loudness.Meter(48000, 1).apply { add(tone(1000.0, 0.5, 3.0, channels = 1)) }.result().power)
        assertEquals(alone, withPause, 0.3)
    }

    @Test
    fun theGainStopsBelowClipping() {
        assertEquals(2f, Loudness.gainTo(-14.0, -20.0206, 0.1f), 0.01f)
        assertTrue(Loudness.gainTo(-14.0, -30.0, 0.5f) <= 0.8913f / 0.5f + 1e-3f)
    }

    @Test
    fun musicGoesDownUnderSoundAndBackUp() {
        val loud = Math.pow(10.0, (-20 + 0.691) / 10).toFloat()
        val sound = FloatArray(100) { if (it in 30..50) loud else 0f }
        val g = Ducking.envelope(sound)
        assertEquals(1f, g[0], 1e-6f)
        assertTrue(g[40] < 0.3f)
        // Already going down just before the sound starts.
        assertTrue(g[29] < 1f)
        assertEquals(1f, g[99], 1e-6f)
    }
}
