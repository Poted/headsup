package app.headsup.core.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.math.abs
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
    fun aDimSharpSceneIsAsSharpAsABrightOne() {
        // The same sharp scene at a fifth of the contrast (a dim landscape) against a blurred bright one.
        val dim = scene(8).map { p -> val v = (p and 0xFF) / 5 + 40; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }.toIntArray()
        val dimSharp = f(dim).sharpness
        assertTrue(dimSharp > f(scene(8, blur = 2)).sharpness)
        assertTrue(dimSharp > 0.6f * f(scene(8)).sharpness)
    }

    @Test
    fun onlyEligiblePhotosAreSuggested() {
        val r = PhotoCull.choose(listOf(f(scene(8)), f(scene(24, tint = 60))), eligible = { it != 1 })
        assertTrue(0 in r.suggested)
        assertTrue(1 !in r.suggested)
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

    /** Straight light and dark bands (soft, as a camera sees them) turned by [degrees]. */
    private fun bands(degrees: Double): FloatArray {
        val a = Math.toRadians(degrees)
        return FloatArray(w * h) { i ->
            val x = i % w - w / 2.0
            val y = i / w - h / 2.0
            val along = -x * kotlin.math.sin(a) + y * kotlin.math.cos(a)
            (0.5 + 0.35 * kotlin.math.sin(along / 4.0)).toFloat()
        }
    }

    @Test
    fun aCrookedHorizonIsTold() {
        assertEquals(0f, PhotoCull.tiltOf(bands(0.0), w, h)!!, 0.6f)
        assertEquals(5f, PhotoCull.tiltOf(bands(5.0), w, h)!!, 1f)
        assertEquals(-4f, PhotoCull.tiltOf(bands(-4.0), w, h)!!, 1f)
    }

    @Test
    fun aSoftPhotoAmongSharpOnesIsNotSuggested() {
        val r = PhotoCull.choose(listOf(f(scene(8)), f(scene(24, tint = 60, blur = 4)), f(scene(12, tint = -50))))
        assertTrue(0 in r.suggested)
        assertTrue(1 !in r.suggested)
    }

    /** [px] smeared sideways over [len] pixels (the camera moved while it took the photo). */
    private fun shaken(px: IntArray, len: Int): IntArray = IntArray(px.size) { i ->
        val x = i % w
        var s = 0
        var n = 0
        for (d in 0 until len) { val xx = x + d; if (xx < w) { s += px[i + d] and 0xFF; n++ } }
        val v = s / n.coerceAtLeast(1)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun aShakenPhotoIsSoft() {
        val sharp = f(scene(8))
        val shake = f(shaken(scene(8), 6))
        assertTrue(shake.sharpness < 0.7f * sharp.sharpness, "${shake.sharpness} vs ${sharp.sharpness}")
        // Two takes of one shot: the shaken one is never the one suggested, nor with it.
        val r = PhotoCull.choose(listOf(shake, sharp, f(scene(24, tint = 60))))
        assertTrue(0 !in r.suggested)
        assertTrue(1 in r.suggested)
    }

    @Test
    fun nothingToChooseFrom() {
        assertTrue(PhotoCull.choose(emptyList()).suggested.isEmpty())
    }
}
