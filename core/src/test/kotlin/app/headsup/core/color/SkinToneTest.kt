package app.headsup.core.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkinToneTest {
    @Test
    fun `typical skin sits near the 123 degree line`() {
        val c = SkinTone.chroma(0.8f, 0.6f, 0.5f)
        assertTrue(abs(SkinTone.angleDifference(c.angleDegrees, SkinTone.LINE_DEGREES)) < 5, "angle ${c.angleDegrees}")
    }

    @Test
    fun `rotation moves skin hue and keeps brightness`() {
        val rgb = floatArrayOf(0.8f, 0.6f, 0.5f)
        val before = SkinTone.chroma(rgb[0], rgb[1], rgb[2])
        val luma = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
        SkinTone.rotate(rgb, 10f)
        val after = SkinTone.chroma(rgb[0], rgb[1], rgb[2])
        assertEquals(10.0, SkinTone.angleDifference(after.angleDegrees, before.angleDegrees), 1.0)
        assertEquals(luma, 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2], 1e-4f)
    }

    @Test
    fun `greys and blues are left alone`() {
        val grey = floatArrayOf(0.5f, 0.5f, 0.5f).also { SkinTone.rotate(it, 20f) }
        assertEquals(listOf(0.5f, 0.5f, 0.5f), grey.toList())
        val blue = floatArrayOf(0.1f, 0.3f, 0.8f).also { SkinTone.rotate(it, 20f) }
        assertEquals(listOf(0.1f, 0.3f, 0.8f), blue.toList())
    }

    @Test
    fun `skin hue is part of the baked table and can be read back`() {
        val cube = LutBaker.bake(null, ColorGrade(skinHue = 15f), size = 17)
        val out = FloatArray(3)
        LutBaker.sampleBaked(cube, 0.8f, 0.6f, 0.5f, out)
        val before = SkinTone.chroma(0.8f, 0.6f, 0.5f)
        val after = SkinTone.chroma(out[0], out[1], out[2])
        assertTrue(SkinTone.angleDifference(after.angleDegrees, before.angleDegrees) > 8)
    }

    @Test
    fun `sampling an identity table returns the input`() {
        val cube = LutBaker.bake(null, ColorGrade(), size = 9)
        val out = FloatArray(3)
        LutBaker.sampleBaked(cube, 0.3f, 0.55f, 0.9f, out)
        assertEquals(0.3f, out[0], 0.01f)
        assertEquals(0.55f, out[1], 0.01f)
        assertEquals(0.9f, out[2], 0.01f)
    }
}
