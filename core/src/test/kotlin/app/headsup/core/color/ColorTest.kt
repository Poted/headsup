package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ColorTest {
    /** 2×2×2 cube that swaps red and blue, written as a `.cube` file (red changes fastest). */
    private val swapRedBlue = buildString {
        appendLine("# test")
        appendLine("TITLE \"Swap\"")
        appendLine("LUT_3D_SIZE 2")
        for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("$b.0 $g.0 $r.0")
    }

    @Test
    fun `cube file is parsed with red changing fastest`() {
        val lut = CubeLut.parse(swapRedBlue)
        assertEquals(2, lut.size)
        assertEquals("Swap", lut.title)
        val out = FloatArray(3)
        lut.sample(1f, 0f, 0f, out)
        assertEquals(listOf(0f, 0f, 1f), out.toList())
        lut.sample(0.25f, 0.5f, 0.75f, out)
        assertEquals(0.75f, out[0], 1e-5f)
        assertEquals(0.5f, out[1], 1e-5f)
        assertEquals(0.25f, out[2], 1e-5f)
    }

    @Test
    fun `domain is normalised`() {
        val text = buildString {
            appendLine("LUT_3D_SIZE 2")
            appendLine("DOMAIN_MIN 0 0 0")
            appendLine("DOMAIN_MAX 2 2 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("${r * 2} ${g * 2} ${b * 2}")
        }
        val out = FloatArray(3)
        CubeLut.parse(text).sample(1f, 1f, 1f, out)
        assertEquals(listOf(1f, 1f, 1f), out.toList())
    }

    @Test
    fun `malformed cube files are rejected`() {
        assertFailsWith<CubeFormatException> { CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n") }
        assertFailsWith<CubeFormatException> { CubeLut.parse("0 0 0\n") }
        assertFailsWith<CubeFormatException> { CubeLut.parse("LUT_1D_SIZE 4\n") }
    }

    @Test
    fun `neutral grade without LUT is identity`() {
        val cube = LutBaker.bake(null, ColorGrade(), size = 5)
        assertEquals(0xFF000000.toInt(), cube[0][0][0])
        assertEquals(0xFFFFFFFF.toInt(), cube[4][4][4])
        assertEquals(0xFF804000.toInt(), cube[2][1][0])
    }

    @Test
    fun `baked cube applies the LUT indexed by r g b`() {
        val cube = LutBaker.bake(CubeLut.parse(swapRedBlue), ColorGrade(), size = 2)
        assertEquals(0xFF0000FF.toInt(), cube[1][0][0]) // pure red in, pure blue out
    }

    @Test
    fun `exposure brightens and saturation minus one gives grey`() {
        val rgb = floatArrayOf(0.4f, 0.4f, 0.4f)
        LutBaker.apply(null, ColorGrade(exposure = 1f), rgb)
        assertTrue(rgb[0] > 0.5f)

        val colour = floatArrayOf(0.8f, 0.2f, 0.1f)
        LutBaker.apply(null, ColorGrade(saturation = -1f), colour)
        assertEquals(colour[0], colour[1], 1e-5f)
        assertEquals(colour[1], colour[2], 1e-5f)
    }

    @Test
    fun `shadows lift dark tones more than bright ones`() {
        val dark = floatArrayOf(0.1f, 0.1f, 0.1f)
        val bright = floatArrayOf(0.9f, 0.9f, 0.9f)
        LutBaker.apply(null, ColorGrade(shadows = 1f), dark)
        LutBaker.apply(null, ColorGrade(shadows = 1f), bright)
        assertTrue(dark[0] - 0.1f > 0.15f)
        assertTrue(bright[0] - 0.9f < 0.01f)
    }

    @Test
    fun `output stays in range`() {
        val rgb = floatArrayOf(1f, 1f, 1f)
        LutBaker.apply(null, ColorGrade(exposure = 2f, brightness = 1f, contrast = 1f), rgb)
        assertEquals(listOf(1f, 1f, 1f), rgb.toList())
    }

    /** A "log to display" stand-in that brightens 1.5× and clips, like a real camera LUT does. */
    private val clippingLut = CubeLut.parse(buildString {
        appendLine("LUT_3D_SIZE 33")
        for (b in 0..32) for (g in 0..32) for (r in 0..32) {
            fun v(i: Int) = minOf(1f, i / 32f * 1.5f)
            appendLine("${v(r)} ${v(g)} ${v(b)}")
        }
    })

    @Test
    fun `with a LUT corrections act before it so clipped highlights can be recovered`() {
        fun out(input: Float, grade: ColorGrade): Float {
            val rgb = floatArrayOf(input, input, input)
            LutBaker.apply(clippingLut, grade, rgb)
            return rgb[0]
        }
        // Both inputs clip without correction...
        assertEquals(1f, out(0.70f, ColorGrade()))
        assertEquals(1f, out(0.72f, ColorGrade()))
        // ...but lowering exposure before the LUT keeps them apart.
        val darker = ColorGrade(exposure = -1f)
        assertTrue(out(0.70f, darker) < out(0.72f, darker))
        assertTrue(out(0.72f, darker) < 1f)
    }

    @Test
    fun `exposure is a log offset when a LUT is used`() {
        val identity = CubeLut.parse(buildString {
            appendLine("LUT_3D_SIZE 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("$r $g $b")
        })
        val rgb = floatArrayOf(0.4f, 0.4f, 0.4f)
        LutBaker.apply(identity, ColorGrade(exposure = 1f), rgb)
        assertEquals(0.4f + LutBaker.LOG_STOP, rgb[0], 1e-5f)
    }
}
