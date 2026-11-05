package app.headsup.core.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhotoCullTest {
    private val w = 96
    private val h = 64

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** A checkered scene ([cell] px squares), optionally blurred by [blur] box passes, shifted by [shift]. */
    private fun scene(cell: Int, blur: Int = 0, shift: Int = 0, tint: Int = 0, dark: Boolean = false): IntArray {
        var y = FloatArray(w * h) { i ->
            val x = i % w + shift
            val yy = i / w
            val base = if ((x / cell + yy / cell) % 2 == 0) 0.75f else 0.3f
            // A gradient across, so the layout of the picture is not just the checks.
            base * (0.6f + 0.4f * (i % w) / w)
        }
        repeat(blur) {
            val o = y.copyOf()
            for (yy in 1 until h - 1) for (x in 1 until w - 1) {
                var s = 0f
                for (dy in -1..1) for (dx in -1..1) s += y[(yy + dy) * w + x + dx]
                o[yy * w + x] = s / 9f
            }
            y = o
        }
        return IntArray(w * h) { i ->
            val v = ((if (dark) y[i] * 0.02f else y[i]) * 255f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or ((v + tint).coerceIn(0, 255) shl 16) or (v shl 8) or (v - tint).coerceIn(0, 255)
        }
    }

    private fun f(px: IntArray) = PhotoCull.features(px, w, h)

    @Test
    fun sharperMeasuresSharper() {
        assertTrue(f(scene(8)).sharpness > f(scene(8, blur = 3)).sharpness)
    }

    @Test
    fun burntOutIsBadlyExposed() {
        val white = IntArray(w * h) { grey(255) }
        assertTrue(f(white).exposure < 0.1f)
        assertTrue(f(scene(8)).exposure > 0.9f)
    }

    @Test
    fun takesOfOneShotGroupAndTheSharpestIsSuggested() {
        val a = f(scene(8, blur = 2))
        val b = f(scene(8))
        val c = f(scene(8, blur = 1, shift = 1))
        val other = f(scene(24, tint = 60))
        val r = PhotoCull.choose(listOf(a, b, c, other))
        assertEquals(r.group[0], r.group[1])
        assertEquals(r.group[1], r.group[2])
        assertNotEquals(r.group[2], r.group[3])
        assertTrue(1 in r.suggested)
        assertTrue(0 !in r.suggested)
        assertTrue(3 in r.suggested)
    }

    @Test
    fun aBlackFrameIsNotSuggested() {
        val r = PhotoCull.choose(listOf(f(scene(8)), f(scene(24, tint = 60)), f(scene(8, dark = true, tint = 0, shift = 5))))
        assertTrue(2 !in r.suggested)
    }

    @Test
    fun nothingToChooseFrom() {
        assertTrue(PhotoCull.choose(emptyList()).suggested.isEmpty())
    }
}
