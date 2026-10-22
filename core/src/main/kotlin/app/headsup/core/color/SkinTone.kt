package app.headsup.core.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Vectorscope maths (BT.709 Cb/Cr) and the skin-tone correction.
 *
 * On a vectorscope, skin of every complexion falls close to one line, about 123° counter-clockwise
 * from the +Cb axis (between red and yellow). The correction rotates hues near that line, leaving
 * neutral greys and unrelated colours (sky, foliage) alone.
 */
object SkinTone {
    const val LINE_DEGREES = 123.0

    /** Position of a colour on the vectorscope. */
    data class Chroma(val angleDegrees: Double, val magnitude: Double, val cb: Double, val cr: Double)

    fun chroma(r: Float, g: Float, b: Float): Chroma {
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val cb = (b - y) / 1.8556
        val cr = (r - y) / 1.5748
        val angle = Math.toDegrees(atan2(cr, cb)).let { if (it < 0) it + 360 else it }
        return Chroma(angle, hypot(cb, cr), cb, cr)
    }

    /** Signed difference a − b in degrees, in −180..180. */
    fun angleDifference(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    /** Rotates skin-like hues by up to [degrees] (positive towards yellow, negative towards red), in place. */
    fun rotate(rgb: FloatArray, degrees: Float) {
        if (degrees == 0f) return
        val c = chroma(rgb[0], rgb[1], rgb[2])
        if (c.magnitude < 1e-4) return
        val distance = abs(angleDifference(c.angleDegrees, LINE_DEGREES))
        val weight = (1 - smoothstep(25.0, 55.0, distance)) * smoothstep(0.005, 0.03, c.magnitude)
        if (weight <= 0) return
        val theta = Math.toRadians(degrees * weight)
        val cb = c.cb * cos(theta) - c.cr * sin(theta)
        val cr = c.cb * sin(theta) + c.cr * cos(theta)
        val y = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
        val r = y + 1.5748 * cr
        val b = y + 1.8556 * cb
        val g = (y - 0.2126 * r - 0.0722 * b) / 0.7152
        rgb[0] = r.toFloat()
        rgb[1] = g.toFloat()
        rgb[2] = b.toFloat()
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
