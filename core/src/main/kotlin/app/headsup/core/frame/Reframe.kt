package app.headsup.core.frame

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Reframing of a clip in the timeline's frame: the output shape ([aspect], e.g. 9:16 for a reel,
 * the timeline's), whether the picture fills the frame (cropped) or [fit]s inside it (bars),
 * zoom, which part is at the centre, and straightening. When filling, zoom never drops below
 * what keeps the frame filled after straightening.
 *
 * Sizes: "source" is the clip's picture, "output" the frame shown/exported. Without [aspect] they
 * are the same; with it the output is the largest frame of that shape inside the source (fill)
 * or the smallest around it (fit).
 */
data class Reframe(
    /** 1 = the output frame shows as much of the picture as fits. */
    val zoom: Float = 1f,
    /** Point of the source picture shown at the output's centre, 0..1 of its width and height. */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    /** Rotation in degrees, positive clockwise, e.g. to level a horizon. */
    val angleDegrees: Float = 0f,
    /** Output width / height, or null to keep the clip's own shape. */
    val aspect: Float? = null,
    /** The whole picture inside the frame, with bars where the shapes differ (instead of cropping). */
    val fit: Boolean = false,
    /** With [fit]: the bars filled with the picture itself, scaled to cover the frame and blurred (as social apps do). */
    val blur: Boolean = false,
    /** Perspective correction, −1..1 ([Perspective]): lines leaning together up/down, and sideways. */
    val vertical: Float = 0f,
    val horizontal: Float = 0f,
    /** Lens distortion corrected, −1..1 ([Lens]): + pulls a barrel's bulge in, − a pincushion out. */
    val distortion: Float = 0f,
    /** The lens's darker corners brightened (+) or darkened (−), −1..1 ([Lens]). */
    val lensVignette: Float = 0f,
    /** Mirrored left to right (before it is turned); a flip upside down is this and half a turn. */
    val mirrored: Boolean = false,
) {
    val isIdentity: Boolean get() = zoom == 1f && centerX == 0.5f && centerY == 0.5f && angleDegrees == 0f && aspect == null && vertical == 0f && horizontal == 0f &&
        distortion == 0f && lensVignette == 0f && !mirrored

    /**
     * Where each point of the corrected picture comes from ([Perspective.sourceOf]), row by row, 3×3;
     * with lens corrections also their two amounts after it ([Lens.K], [Lens.VIGNETTE]); null without
     * correction.
     */
    val warp: DoubleArray? get() {
        val m = Perspective.sourceOf(vertical, horizontal)
        if (distortion == 0f && lensVignette == 0f) return m
        return (m ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) + doubleArrayOf(Lens.k(distortion).toDouble(), lensVignette.toDouble())
    }

    /**
     * Quarter turns in [angleDegrees] (the picture turned by 90° steps; −1 a turn to the left), and
     * what is left of the angle besides them (straightening, within ±45°).
     */
    val quarterTurns: Int get() = Math.round(angleDegrees / 90f)
    val straighten: Float get() = angleDegrees - 90f * quarterTurns

    /** This with the picture turned a further quarter to the right ([steps] > 0) or left. */
    fun turned(steps: Int): Reframe {
        val q = ((quarterTurns + steps) % 4 + 4) % 4
        // Kept within −90…180 so the angle never grows round and round.
        val turns = if (q == 3) -1 else q
        return copy(angleDegrees = straighten + 90f * turns, centerX = 0.5f, centerY = 0.5f)
    }

    /** Mirrored left to right, or back. */
    fun mirroredLeftRight(): Reframe = copy(mirrored = !mirrored, centerX = 1f - centerX)

    /** Flipped upside down, or back: mirrored and turned half round. */
    fun flippedUpsideDown(): Reframe = mirroredLeftRight().turned(2)

    /** Output frame size for a [srcW]×[srcH] clip, in its pixels: inside the picture (fill) or around it (fit). */
    fun outputSize(srcW: Float, srcH: Float): Pair<Float, Float> {
        // Turned a quarter (or three): the picture stands the other way, its own shape too.
        val odd = quarterTurns % 2 != 0
        val w = if (odd) srcH else srcW
        val h = if (odd) srcW else srcH
        return outputSizeUpright(w, h)
    }

    private fun outputSizeUpright(srcW: Float, srcH: Float): Pair<Float, Float> {
        val a = aspect ?: return srcW to srcH
        val wider = a > srcW / srcH
        return if (fit) {
            if (wider) srcH * a to srcH else srcW to srcW / a
        } else {
            if (wider) srcW to srcW / a else srcH * a to srcH
        }
    }

    /**
     * Zoom actually used: at least the scale at which the rotated picture still covers the
     * output frame. [outW]×[outH] and [srcW]×[srcH] may be in different units (e.g. a preview box
     * and the picture laid out inside it); the picture is first scaled to just cover the output.
     */
    fun effectiveZoom(outW: Float, outH: Float, srcW: Float, srcH: Float): Float {
        val s0 = baseScale(outW, outH, srcW, srcH)
        val (c, s) = cosSin()
        if (fit) {
            // The (turned) picture just inside the frame at zoom 1; zooming in from there is free.
            val inside = min(outW / (srcW * c + srcH * s), outH / (srcW * s + srcH * c)) / s0
            return zoom * inside
        }
        val cover = max((outW * c + outH * s) / (s0 * srcW), (outW * s + outH * c) / (s0 * srcH))
        // Turned a quarter, zoom 1 is the turned picture just covering the frame (the base scale
        // is the upright picture's, so it is put right here).
        val upright = if (quarterTurns % 2 != 0) baseScale(outW, outH, srcH, srcW) / s0 else 1f
        return max(zoom * upright, cover)
    }

    /** [centerX]/[centerY] limited so the output stays inside the picture. */
    fun clamped(outW: Float, outH: Float, srcW: Float, srcH: Float): Reframe {
        val scale = baseScale(outW, outH, srcW, srcH) * effectiveZoom(outW, outH, srcW, srcH)
        val (c, s) = cosSin()
        // Half-extent of the (rotated) output frame in source fractions.
        val hx = (outW * c + outH * s) / (2f * scale) / srcW
        val hy = (outW * s + outH * c) / (2f * scale) / srcH
        return copy(
            centerX = if (hx >= 0.5f) 0.5f else centerX.coerceIn(hx, 1f - hx),
            centerY = if (hy >= 0.5f) 0.5f else centerY.coerceIn(hy, 1f - hy),
        )
    }

    /** Source pixel shown at output pixel ([x], [y]). */
    fun sourceOf(x: Float, y: Float, outW: Float, outH: Float, srcW: Float, srcH: Float): Pair<Float, Float> {
        val r = clamped(outW, outH, srcW, srcH)
        val scale = baseScale(outW, outH, srcW, srcH) * r.effectiveZoom(outW, outH, srcW, srcH)
        val a = Math.toRadians(angleDegrees.toDouble())
        val dx = (x - outW / 2f) / scale
        val dy = (y - outH / 2f) / scale
        // Undo the clockwise rotation (screen coordinates, y down).
        val ux = (dx * cos(a) + dy * sin(a)).toFloat()
        // Mirrored: the picture's left is on the right.
        val sx = if (mirrored) -ux else ux
        val sy = (-dx * sin(a) + dy * cos(a)).toFloat()
        return (r.centerX * srcW + sx) to (r.centerY * srcH + sy)
    }

    /** Output pixel where source pixel ([x], [y]) is shown: the inverse of [sourceOf]. */
    fun frameOf(x: Float, y: Float, outW: Float, outH: Float, srcW: Float, srcH: Float): Pair<Float, Float> {
        val r = clamped(outW, outH, srcW, srcH)
        val scale = baseScale(outW, outH, srcW, srcH) * r.effectiveZoom(outW, outH, srcW, srcH)
        val a = Math.toRadians(angleDegrees.toDouble())
        val sx = x - r.centerX * srcW
        val sy = y - r.centerY * srcH
        val ux = if (mirrored) -sx else sx
        // Turned clockwise (screen coordinates, y down).
        val dx = (ux * cos(a) - sy * sin(a)).toFloat()
        val dy = (ux * sin(a) + sy * cos(a)).toFloat()
        return (outW / 2f + dx * scale) to (outH / 2f + dy * scale)
    }

    // Same-size output (no aspect change), as used by most callers.
    fun effectiveZoom(width: Float, height: Float) = effectiveZoom(width, height, width, height)
    fun clamped(width: Float, height: Float) = clamped(width, height, width, height)
    fun sourceOf(x: Float, y: Float, width: Float, height: Float) = sourceOf(x, y, width, height, width, height)

    private fun cosSin(): Pair<Float, Float> {
        val a = Math.toRadians(angleDegrees.toDouble())
        return abs(cos(a)).toFloat() to abs(sin(a)).toFloat()
    }

    companion object {
        /** Scale at which the picture just covers the output frame. */
        fun baseScale(outW: Float, outH: Float, srcW: Float, srcH: Float) = max(outW / srcW, outH / srcH)

        /** Output shapes offered in the app, with their labels; null = the clip's own. */
        val PRESETS: List<Pair<Float?, String>> = listOf(
            null to "Original",
            16f / 9f to "16:9",
            4f / 3f to "4:3",
            1f to "1:1",
            4f / 5f to "4:5",
            9f / 16f to "9:16",
        )
    }
}
