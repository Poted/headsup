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
        // The random scene has a few skin-like pixels: at most a hair of turn.
        assertEquals(0f, matched.skinHue, 2f)
    }

    @Test
    fun `a green field is not taken for a green cast, a warm light is`() {
        fun stats(pixels: List<Triple<Float, Float, Float>>) = MatchStats.of(pixels.flatMap { listOf(it.first, it.second, it.third) }.toFloatArray())
        val rnd = Random(3)
        fun grey() = (0.3f + rnd.nextFloat() * 0.4f).let { Triple(it, it, it) }
        // Neutral light: a grey street, and half the frame a green field.
        val greyStreet = List(2000) { grey() }
        val withField = List(1000) { grey() } + List(1000) { Triple(0.25f, 0.55f + rnd.nextFloat() * 0.1f, 0.2f) }
        val a = stats(greyStreet)
        val b = stats(withField)
        assertEquals(a.cb, b.cb, 0.01f)
        assertEquals(a.cr, b.cr, 0.01f)
        // A warm light on the same street is a cast.
        val warm = stats(greyStreet.map { (r, g, bl) -> Triple((r * 1.15f).coerceAtMost(1f), g, bl * 0.85f) })
        assertTrue(warm.cr - a.cr > 0.02f && warm.cb - a.cb < -0.02f, "${warm.cr} ${warm.cb}")
    }
}
