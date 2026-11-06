package app.headsup.core.color

/** A clip's corrections at a moment of its file ([timeMs]), for corrections that change over the shot. */
data class GradeKey(val timeMs: Long, val grade: ColorGrade)

/**
 * Corrections changing over a shot (walking out of shade into sun): between two keys every
 * value goes smoothly from one to the other; before the first and after the last the nearest
 * key holds. What cannot be blended (curves, a look, the hues of toning and wheels) changes
 * half way between the keys.
 */
object GradeKeys {
    /** Keys closer than this to the moment edited are changed rather than added. */
    const val SAME_MS = 80L

    /** The corrections at [timeMs]; null without keys. */
    fun at(keys: List<GradeKey>, timeMs: Long): ColorGrade? {
        if (keys.isEmpty()) return null
        if (timeMs <= keys.first().timeMs) return keys.first().grade
        if (timeMs >= keys.last().timeMs) return keys.last().grade
        val next = keys.indexOfFirst { it.timeMs > timeMs }
        val a = keys[next - 1]
        val b = keys[next]
        val t = (timeMs - a.timeMs).toFloat() / (b.timeMs - a.timeMs).coerceAtLeast(1L)
        // Eased in and out, as a grade changes on a cine monitor.
        return blend(a.grade, b.grade, t * t * (3f - 2f * t))
    }

    /** [a] going to [b] by [f] (0..1). */
    fun blend(a: ColorGrade, b: ColorGrade, f: Float): ColorGrade {
        if (f <= 0f) return a
        if (f >= 1f) return b
        val near = if (f < 0.5f) a else b
        fun mix(x: Float, y: Float) = x + (y - x) * f
        fun mixList(x: List<Float>, y: List<Float>) = List(maxOf(x.size, y.size)) { i -> mix(x.getOrElse(i) { 0f }, y.getOrElse(i) { 0f }) }
        return near.copy(
            exposure = mix(a.exposure, b.exposure), brightness = mix(a.brightness, b.brightness), contrast = mix(a.contrast, b.contrast),
            shadows = mix(a.shadows, b.shadows), highlights = mix(a.highlights, b.highlights), saturation = mix(a.saturation, b.saturation),
            temperature = mix(a.temperature, b.temperature), vignette = mix(a.vignette, b.vignette), blacks = mix(a.blacks, b.blacks),
            whites = mix(a.whites, b.whites), vibrance = mix(a.vibrance, b.vibrance), tint = mix(a.tint, b.tint), skinHue = mix(a.skinHue, b.skinHue),
            rolloff = mix(a.rolloff, b.rolloff), toneAmount = mix(a.toneAmount, b.toneAmount), tame = mix(a.tame, b.tame),
            mixer = mixList(a.mixer, b.mixer),
            lookAmount = if (a.look == b.look) mix(a.lookAmount, b.lookAmount) else near.lookAmount,
            // A wheel's hue is not blended (it would sweep through other colours); its strength and brightness are.
            wheels = List(maxOf(a.wheels.size, b.wheels.size)) { i ->
                if (i % 3 == 0 && i < 9) near.wheels.getOrElse(i) { 0f } else mix(a.wheels.getOrElse(i) { 0f }, b.wheels.getOrElse(i) { 0f })
            },
            texture = mix(a.texture, b.texture), clarity = mix(a.clarity, b.clarity), dehaze = mix(a.dehaze, b.dehaze),
            channelMix = mixList(a.channelMix, b.channelMix),
            bw = List(maxOf(a.bw.size, b.bw.size)) { i -> if (i == 0) near.bw.getOrElse(0) { 0f } else mix(a.bw.getOrElse(i) { 0f }, b.bw.getOrElse(i) { 0f }) },
            calibration = mixList(a.calibration, b.calibration),
            glow = mix(a.glow, b.glow), halation = mix(a.halation, b.halation), density = mix(a.density, b.density),
            defringe = mix(a.defringe, b.defringe),
        )
    }

    /** [keys] with [grade] at [timeMs]: the key there (within [SAME_MS]) changed, or a new one, in time order. */
    fun set(keys: List<GradeKey>, timeMs: Long, grade: ColorGrade): List<GradeKey> {
        val i = keys.indexOfFirst { kotlin.math.abs(it.timeMs - timeMs) <= SAME_MS }
        return if (i >= 0) keys.toMutableList().apply { set(i, GradeKey(keys[i].timeMs, grade)) }
        else (keys + GradeKey(timeMs, grade)).sortedBy { it.timeMs }
    }

    /** [keys] without the one at [timeMs] (within [SAME_MS]). */
    fun remove(keys: List<GradeKey>, timeMs: Long): List<GradeKey> = keys.filter { kotlin.math.abs(it.timeMs - timeMs) > SAME_MS }

    /** The key at [timeMs] (within [SAME_MS]), if any. */
    fun keyAt(keys: List<GradeKey>, timeMs: Long): GradeKey? = keys.firstOrNull { kotlin.math.abs(it.timeMs - timeMs) <= SAME_MS }
}
