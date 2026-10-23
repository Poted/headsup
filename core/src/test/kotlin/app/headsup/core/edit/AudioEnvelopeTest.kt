package app.headsup.core.edit

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioEnvelopeTest {
    private val rate = 8_000

    /** Quiet hum with a loud hit every [everyMs]. */
    private fun hits(totalMs: Int, everyMs: Int): FloatArray = FloatArray(rate * totalMs / 1000) { i ->
        val ms = i * 1000 / rate
        val hit = ms % everyMs < 60
        (if (hit) 0.8f else 0.05f) * sin(2 * PI * 220 * i / rate).toFloat()
    }

    @Test
    fun `finds regular hits as accents`() {
        val env = AudioEnvelope.fromSamples(hits(5_000, 500), rate)
        val accents = env.accents()
        assertTrue(accents.size in 8..10, "found ${accents.size}")
        for (a in accents) assertTrue(a.ms % 500 < 60 || a.ms % 500 > 460, "accent at ${a.ms}")
    }

    @Test
    fun `steady sound has no accents`() {
        val steady = FloatArray(rate * 3) { i -> 0.5f * sin(2 * PI * 220 * i / rate).toFloat() }
        assertEquals(0, AudioEnvelope.fromSamples(steady, rate).accents().size)
    }

    @Test
    fun `peak covers a span`() {
        val env = AudioEnvelope(floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f), 20)
        assertEquals(0.9f, env.peak(0, 60))
        assertEquals(0.3f, env.peak(40, 80))
    }
}
