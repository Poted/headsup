package app.headsup.core.color

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShotMatchTest {
    private fun argb(r: Float, g: Float, b: Float) =
        (0xFF shl 24) or ((r * 255).toInt().coerceIn(0, 255) shl 16) or ((g * 255).toInt().coerceIn(0, 255) shl 8) or (b * 255).toInt().coerceIn(0, 255)

    /** A varied scene: random colours around grey. */
    private val scene: IntArray = Random(7).let { rnd ->
        IntArray(4000) {
            val y = rnd.nextFloat() * 0.8f + 0.1f
            argb(y + rnd.nextFloat() * 0.1f - 0.05f, y + rnd.nextFloat() * 0.1f - 0.05f, y + rnd.nextFloat() * 0.1f - 0.05f)
        }
    }

    private fun statsThrough(grade: ColorGrade): MatchStats {
        val rgb = FloatArray(scene.size * 3)
        val c = FloatArray(3)
        scene.forEachIndexed { i, p ->
            c[0] = ((p shr 16) and 0xFF) / 255f
            c[1] = ((p shr 8) and 0xFF) / 255f
            c[2] = (p and 0xFF) / 255f
            LutBaker.apply(null, grade, c)
            rgb[3 * i] = c[0]
            rgb[3 * i + 1] = c[1]
            rgb[3 * i + 2] = c[2]
        }
        return MatchStats.of(rgb)
    }

    @Test
    fun `recovers a known exposure and white balance difference`() {
        // The "reference camera" is the same scene, brighter and warmer.
        val reference = statsThrough(ColorGrade(exposure = 0.6f, temperature = 0.4f))
        val matched = ShotMatch.match(scene, null, ColorGrade(), reference)
        assertEquals(0.6f, matched.exposure, 0.2f)
        assertTrue(matched.temperature > 0.2f, "temperature ${matched.temperature}")
        val after = statsThrough(matched)
        assertEquals(reference.median, after.median, 0.03f)
        assertEquals(reference.cr, after.cr, 0.01f)
    }

    @Test
    fun `leaves an already matching clip about where it is`() {
        val reference = statsThrough(ColorGrade())
        val matched = ShotMatch.match(scene, null, ColorGrade(), reference)
        assertEquals(0f, matched.exposure, 0.1f)
        assertEquals(0f, matched.temperature, 0.1f)
    }

    /** A scene with a face: a quarter of the pixels are skin tones. */
    private val portrait: IntArray = Random(11).let { rnd ->
        IntArray(4000) {
            if (it % 4 == 0) {
                val k = 0.85f + rnd.nextFloat() * 0.15f
                argb(0.82f * k, 0.6f * k, 0.48f * k)
            } else {
                val y = rnd.nextFloat() * 0.8f + 0.1f
                argb(y, y, y)
            }
        }
    }

    @Test
    fun `turns skin to the reference camera's skin hue`() {
        val rgb = FloatArray(portrait.size * 3)
        val c = FloatArray(3)
        portrait.forEachIndexed { i, p ->
            c[0] = ((p shr 16) and 0xFF) / 255f
            c[1] = ((p shr 8) and 0xFF) / 255f
            c[2] = (p and 0xFF) / 255f
            LutBaker.apply(null, ColorGrade(skinHue = 10f), c)
            rgb[3 * i] = c[0]
            rgb[3 * i + 1] = c[1]
            rgb[3 * i + 2] = c[2]
        }
        val reference = MatchStats.of(rgb)
        assertTrue(reference.skinAngle != null)
        val matched = ShotMatch.match(portrait, null, ColorGrade(), reference)
        assertEquals(10f, matched.skinHue, 3f)
    }

    @Test
    fun `no skin in the frame leaves skin hue alone`() {
        val matched = ShotMatch.match(scene, null, ColorGrade(), statsThrough(ColorGrade(exposure = 0.3f)))
        assertEquals(0f, matched.skinHue)
    }
}
