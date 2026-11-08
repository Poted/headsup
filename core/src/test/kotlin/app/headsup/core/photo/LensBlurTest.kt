package app.headsup.core.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LensBlurTest {
    private val w = 80
    private val h = 60

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun theBackgroundIsBlurredAndTheSubjectLeftOut() {
        // A dark background with one bright point, and a mid-grey subject on the left.
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; when { x < 20 -> grey(128); x == 60 && y == 30 -> grey(255); else -> grey(20) } }
        val keep = FloatArray(w * h) { i -> if (i % w < 20) 1f else 0f }
        val out = LensBlur.background(px, w, h, keep, radius = 6f, highlights = 1f)
        // The subject is not covered.
        assertEquals(0, out[30 * w + 10] ushr 24)
        // The bright point opened into a disc: its neighbours are lit.
        val near = out[30 * w + 63] and 0xFF
        assertTrue(near > 40, "near $near")
        // The subject's grey does not bleed onto the background next to it.
        val edge = out[30 * w + 22] and 0xFF
        assertTrue(edge < 60, "edge $edge")
    }
}
