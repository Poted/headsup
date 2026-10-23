package app.headsup.core.color

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** What the assistant measured on a frame (display colours, after the current grade). */
data class FrameStats(
    /** Luminance percentiles. */
    val low: Float,
    val median: Float,
    val high: Float,
    /** Share of pixels near white / near black. */
    val clipped: Float,
    val crushed: Float,
    /** Mean chroma (Cb/Cr distance from grey). */
    val colourfulness: Float,
    /** Share of skin-like pixels, and of loud greens/blues. */
    val skin: Float,
    val foliage: Float,
    /** Mean colour cast: positive = warm (red/yellow), negative = cool. */
    val warmth: Float,
)

enum class CineStyle(val label: String) {
    WARM_FILM("Warm film"),
    TEAL_ORANGE("Teal & orange"),
    MUTED("Muted"),
}

/** A suggested look: corrections to add to the clip's, and film texture for its effects. */
data class CineSuggestion(val grade: ColorGrade, val grain: Float, val halation: Float)

/**
 * Suggests a cinematic grade for a frame, without AI: it measures the frame (where the mid-tones
 * sit, how much is clipped, which colours dominate, whether there is skin) and derives the usual
 * moves of a film grade from that: exposure that leaves room above the mid-tones, a highlight
 * shoulder, controlled blacks and contrast, split toning that spares skin, calmer greens and
 * blues, and grain/halation for texture.
 */
object CineAssistant {
    /** [pixels] are ARGB, already through the clip's current colour table. */
    fun analyse(pixels: IntArray): FrameStats {
        val n = pixels.size.coerceAtLeast(1)
        val histogram = IntArray(256)
        var clipped = 0
        var crushed = 0
        var chromaSum = 0.0
        var skin = 0.0
        var foliage = 0
        var warmth = 0.0
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            histogram[(y * 255).toInt().coerceIn(0, 255)]++
            if (y > 0.97f) clipped++
            if (y < 0.03f) crushed++
            val cb = (b - y) / 1.8556f
            val cr = (r - y) / 1.5748f
            val chroma = sqrt(cb * cb + cr * cr)
            chromaSum += chroma
            skin += FilmLook.skinness(cb, cr, chroma)
            if (chroma > 0.08f) {
                val angle = FilmLook.angleOf(cb, cr)
                if (angle in 190f..270f || angle >= 330f || angle < 10f) foliage++
            }
            warmth += cr - cb
        }
        fun percentile(q: Float): Float {
            val target = (n * q).toInt()
            var count = 0
            for (i in 0..255) {
                count += histogram[i]
                if (count > target) return i / 255f
            }
            return 1f
        }
        return FrameStats(
            low = percentile(0.03f),
            median = percentile(0.5f),
            high = percentile(0.97f),
            clipped = clipped.toFloat() / n,
            crushed = crushed.toFloat() / n,
            colourfulness = (chromaSum / n).toFloat(),
            skin = (skin / n).toFloat(),
            foliage = foliage.toFloat() / n,
            warmth = (warmth / n).toFloat(),
        )
    }

    fun suggest(stats: FrameStats, style: CineStyle): CineSuggestion {
        // Film sits its mid-tones a little lower than video, leaving room for the highlights.
        val targetMedian = when (style) {
            CineStyle.MUTED -> 0.40f
            else -> 0.38f
        }
        val exposure = (stops(targetMedian) - stops(stats.median.coerceIn(0.05f, 0.95f))).coerceIn(-1f, 1f) * 0.7f

        // A shoulder always helps; more when the frame clips.
        val rolloff = (0.35f + stats.clipped * 8f).coerceIn(0.35f, 0.8f)

        // Contrast towards a moderate range; blacks lifted a touch for a print-like toe.
        val range = stats.high - stats.low
        val contrast = when {
            range > 0.85f -> -0.12f
            range < 0.5f -> 0.15f
            else -> 0f
        } + if (style == CineStyle.MUTED) -0.08f else 0f
        val blacks = when (style) {
            CineStyle.MUTED -> 0.25f
            CineStyle.WARM_FILM -> 0.12f
            CineStyle.TEAL_ORANGE -> if (stats.crushed > 0.05f) 0.08f else 0f
        }

        // Loud greens and blues are what most separates video from film colour.
        val tame = (0.25f + stats.foliage * 1.5f).coerceIn(0.25f, 0.7f) + if (style == CineStyle.MUTED) 0.15f else 0f

        // An already warm frame needs less warmth added.
        val warmthRoom = (1f - stats.warmth * 6f).coerceIn(0.4f, 1.2f)
        val grade = when (style) {
            CineStyle.WARM_FILM -> ColorGrade(
                exposure = exposure, contrast = contrast, blacks = blacks, saturation = -0.08f,
                temperature = 0.12f * warmthRoom, rolloff = rolloff, toneAmount = 0.35f,
                shadowHue = 250f, highlightHue = 135f, tame = tame.coerceAtMost(1f),
            )
            CineStyle.TEAL_ORANGE -> ColorGrade(
                exposure = exposure, contrast = contrast + 0.05f, blacks = blacks, saturation = 0.03f,
                rolloff = rolloff, toneAmount = 0.6f * warmthRoom.coerceAtMost(1f),
                shadowHue = ColorGrade.DEFAULT_SHADOW_HUE, highlightHue = ColorGrade.DEFAULT_HIGHLIGHT_HUE,
                tame = tame.coerceAtMost(1f),
            )
            CineStyle.MUTED -> ColorGrade(
                exposure = exposure, contrast = contrast, blacks = blacks, saturation = -0.25f,
                rolloff = rolloff, toneAmount = 0.2f, shadowHue = 270f, highlightHue = 110f,
                tame = tame.coerceAtMost(1f),
            )
        }
        val grain = when (style) {
            CineStyle.MUTED -> 0.3f
            else -> 0.2f
        }
        val halation = if (style == CineStyle.MUTED) 0.15f else 0.3f
        return CineSuggestion(grade, grain, halation)
    }

    /** Stops of a display value (gamma 2.2) relative to white. */
    private fun stops(display: Float) = (ln(display.pow(2.2f)) / ln(2f))
}
