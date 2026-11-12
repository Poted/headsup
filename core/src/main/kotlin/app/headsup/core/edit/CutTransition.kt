package app.headsup.core.edit

/**
 * How one clip goes into the next: half of the transition is the end of the clip before, half the
 * start of the next (the clips do not overlap): through black or white, a zoom (the end pushed in
 * and blurred, the next starting pushed in and settling), or a whip pan (the end flung sideways in
 * a blur of movement, the next arriving from the other side).
 */
enum class CutKind(val label: String) { NONE("Cut"), BLACK("Black"), WHITE("White"), ZOOM("Zoom"), WHIP("Whip") }

/** What a transition does to a frame at one moment. */
data class CutLook(
    /** How much of the frame is the dip's colour (black or white), 0..1. */
    val dip: Float = 0f,
    val dipWhite: Boolean = false,
    /** Extra zoom (1 = none) about the frame's middle. */
    val zoom: Float = 1f,
    /** Sideways move, as a share of the frame's width (+ right). */
    val shift: Float = 0f,
    /** Blur of movement, as a share of the frame's width: sideways for a whip, outwards for a zoom. */
    val blur: Float = 0f,
    /** The blur is outwards from the middle (a zoom) rather than sideways (a whip). */
    val radial: Boolean = false,
) {
    val isNone: Boolean get() = dip == 0f && zoom == 1f && shift == 0f && blur == 0f
}

object CutTransition {
    /** Lengths offered, ms. */
    val LENGTHS = listOf(300L, 500L, 800L, 1200L)

    /**
     * The look of a clip [atMs] into it (timeline time) of [lengthMs]: going out to the next through
     * [out] (its whole length [outMs]) at its end, coming in through [into] ([intoMs]) at its start.
     */
    fun at(atMs: Long, lengthMs: Long, into: CutKind, intoMs: Long, out: CutKind, outMs: Long): CutLook {
        val halfIn = (intoMs / 2).coerceAtMost(lengthMs / 2)
        val halfOut = (outMs / 2).coerceAtMost(lengthMs / 2)
        if (into != CutKind.NONE && halfIn > 0 && atMs < halfIn) {
            // Coming in: 1 at the cut, 0 when settled.
            val p = 1f - atMs.toFloat() / halfIn
            return look(into, p, arriving = true)
        }
        val left = lengthMs - atMs
        if (out != CutKind.NONE && halfOut > 0 && left < halfOut) {
            val p = 1f - left.toFloat() / halfOut
            return look(out, p, arriving = false)
        }
        return CutLook()
    }

    /** At [p] 0..1 towards the cut, arriving (the next clip) or leaving. */
    fun look(kind: CutKind, p: Float, arriving: Boolean): CutLook {
        val q = p.coerceIn(0f, 1f)
        // Quick at the cut, gentle away from it.
        val e = q * q
        return when (kind) {
            CutKind.NONE -> CutLook()
            CutKind.BLACK -> CutLook(dip = q)
            CutKind.WHITE -> CutLook(dip = q, dipWhite = true)
            CutKind.ZOOM -> CutLook(zoom = 1f + 0.6f * e, blur = 0.06f * e, radial = true)
            CutKind.WHIP -> CutLook(shift = (if (arriving) 0.5f else -0.5f) * e, blur = 0.12f * e)
        }
    }
}
