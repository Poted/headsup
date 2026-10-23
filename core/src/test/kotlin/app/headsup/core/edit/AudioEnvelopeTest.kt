package app.headsup.core.edit

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioEnvelopeTest {
    private val rate = 8_000

    /** A quiet hum with a loud hit every [everyMs], starting at [offsetMs]. */
    private fun hits(totalMs: Int, everyMs: Int, offsetMs: Int = 0): FloatArray = FloatArray(rate * totalMs / 1000) { i ->
        val ms = i * 1000 / rate - offsetMs
        val hit = ms >= 0 && ms % everyMs < 60
        (if (hit) 0.8f else 0.05f) * sin(2 * PI * 220 * i / rate).toFloat()
    }

    @Test
    fun `finds the tempo and a grid on the hits`() {
        val r = AudioEnvelope.fromSamples(hits(10_000, 500, offsetMs = 130), rate).rhythm()
        assertNotNull(r.bpm)
        assertEquals(120f, r.bpm!!, 3f)
        for (b in r.beats.drop(1).dropLast(1)) assertTrue(abs((b - 130) % 500) < 45 || abs((b - 130) % 500) > 455, "beat at $b")
        assertTrue(r.accents.size in 15..21, "accents ${r.accents.size}")
    }

    @Test
    fun `steady sound has neither beats nor accents`() {
        val steady = FloatArray(rate * 5) { i -> 0.5f * sin(2 * PI * 220 * i / rate).toFloat() }
        val r = AudioEnvelope.fromSamples(steady, rate).rhythm()
        assertNull(r.bpm)
        assertEquals(0, r.accents.size)
    }

    @Test
    fun `noise has no beat grid and few accents`() {
        val random = Random(1)
        val noise = FloatArray(rate * 8) { (random.nextFloat() - 0.5f) * 0.4f }
        val r = AudioEnvelope.fromSamples(noise, rate).rhythm()
        assertNull(r.bpm)
        assertTrue(r.accents.size < 5, "accents ${r.accents.size}")
    }

    @Test
    fun `peak covers a span`() {
        val env = AudioEnvelope(floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f), 20)
        assertEquals(0.9f, env.peak(0, 60))
        assertEquals(0.3f, env.peak(40, 80))
    }
}
