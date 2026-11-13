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

    /** A colour at vectorscope [angle] (degrees) and chroma [mag], brightness [y]. */
    private fun at(angle: Double, mag: Double, y: Double = 0.45): FloatArray {
        val a = Math.toRadians(angle)
        val cb = mag * kotlin.math.cos(a)
        val cr = mag * kotlin.math.sin(a)
        val r = y + 1.5748 * cr
        val b = y + 1.8556 * cb
        val g = (y - 0.2126 * r - 0.0722 * b) / 0.7152
        return floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
    }

    @Test
    fun `skin measured off the line is turned onto it and the lamplit room stays`() {
        // Skin read at 85° (towards magenta), the room around it orange-yellow at 150°.
        val skin = at(85.0, 0.05).also { SkinTone.rotate(it, 38f, 85f) }
        assertEquals(123.0, SkinTone.chroma(skin[0], skin[1], skin[2]).angleDegrees, 1.0)
        val room = at(150.0, 0.12)
        val before = room.copyOf()
        SkinTone.rotate(room, 38f, 85f)
        for (k in 0..2) assertEquals(before[k], room[k], 1e-6f)
    }

    @Test
    fun `loud colours of the skin's hue are left alone`() {
        val loud = at(90.0, 0.32)
        val before = loud.copyOf()
        SkinTone.rotate(loud, 30f, 90f)
        for (k in 0..2) assertEquals(before[k], loud[k], 1e-6f)
    }
}
