package app.headsup.core.planner

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The neutral density filters offered, with how many stops of light each takes away. */
enum class NdFilter(val label: String, val stops: Double) {
    None("None", 0.0),
    Nd4("ND4", 2.0),
    Nd8("ND8", 3.0),
    Nd16("ND16", 4.0),
    Nd32("ND32", 5.0),
    Nd64("ND64", 6.0),
    Nd1000("ND1000", 10.0),
}

/** What the camera can do: the lowest and highest ISO it offers. */
data class CameraLimits(val isoMin: Double = 100.0, val isoMax: Double = 25_600.0)

/** Which of the three exposure settings the planner works out; the other two are held by the user. */
enum class AutoSetting { Iso, Shutter, Aperture }

/**
 * The exposure triangle as the user sets it: two of aperture, shutter and ISO are held (locked),
 * the third is left to follow the light. [nd] is the filter on the lens, or null to have the
 * planner pick one.
 */
data class ExposureSetup(
    val auto: AutoSetting = AutoSetting.Iso,
    val aperture: Double = 2.8,
    val shutter: Double = 0.5,
    val iso: Double = 100.0,
    val nd: NdFilter? = NdFilter.Nd16,
    val limits: CameraLimits = CameraLimits(),
)

/** Something the user should look at in an exposure: a setting out of the camera's reach. */
enum class ExposureWarning {
    /** The light is too dim: the ISO needed is above what the sensor offers. */
    IsoTooHigh,
    /** The light is too bright: even the lowest ISO overexposes; a stronger filter, a faster shutter or a smaller opening is needed. */
    IsoTooLow,
}

/** The exposure for a moment: all three settings, the filter, and what is wrong with it. */
data class ExposureResult(
    val aperture: Double,
    val shutter: Double,
    val iso: Double,
    val nd: NdFilter,
    /** The ISO the light asks for, before it is held to the camera's limits. */
    val wantedIso: Double,
    val warning: ExposureWarning?,
    /** With a warning: the filter that would bring the exposure into reach (null when none of the listed ones do). */
    val suggestedNd: NdFilter?,
    /** False when even the suggested (strongest) filter leaves the exposure out of reach: the shutter or the aperture must change too. */
    val suggestionEnough: Boolean = true,
)

/** The exposure triangle with neutral density: the arithmetic behind the planner's HUD. */
object Exposure {
    /** EV at ISO 100 of a setting: log2(N² / t) − log2(ISO / 100), with a filter's stops added as the light it takes away. */
    fun ev100(aperture: Double, shutter: Double, iso: Double, ndStops: Double = 0.0): Double =
        log2(aperture * aperture / shutter) - log2(iso / 100.0) + ndStops

    /** The ISO a scene of [ev] (at ISO 100) needs at [aperture] and [shutter] behind a filter of [ndStops]. */
    fun isoFor(ev: Double, aperture: Double, shutter: Double, ndStops: Double): Double =
        100.0 * aperture * aperture * 2.0.pow(ndStops) / (shutter * 2.0.pow(ev))

    /** The shutter time (seconds) a scene of [ev] needs at [aperture] and [iso] behind a filter of [ndStops]. */
    fun shutterFor(ev: Double, aperture: Double, iso: Double, ndStops: Double): Double =
        100.0 * aperture * aperture * 2.0.pow(ndStops) / (iso * 2.0.pow(ev))

    /** The aperture (f-number) a scene of [ev] needs at [shutter] and [iso] behind a filter of [ndStops]. */
    fun apertureFor(ev: Double, shutter: Double, iso: Double, ndStops: Double): Double =
        sqrt(iso * shutter * 2.0.pow(ev) / (100.0 * 2.0.pow(ndStops)))

    /**
     * The neutral density, in stops, that keeps the ISO within the camera's range at this aperture and
     * shutter: from the least (ISO at its highest) to the most (ISO at its lowest). Negative stops
     * mean the light is too dim even without a filter. Answers "what filter for 1 s and ISO 100–3200?".
     */
    fun ndWindow(ev: Double, aperture: Double, shutter: Double, limits: CameraLimits): ClosedFloatingPointRange<Double> {
        val bare = isoFor(ev, aperture, shutter, 0.0)
        return log2(limits.isoMin / bare)..log2(limits.isoMax / bare)
    }

    /** The listed filters whose stops fall inside [window] (so the ISO stays in range). */
    fun filtersIn(window: ClosedFloatingPointRange<Double>): List<NdFilter> =
        NdFilter.entries.filter { it.stops >= window.start - 0.025 && it.stops <= window.endInclusive + 0.025 }

    /** Works the exposure out for a scene of [ev] (at ISO 100). */
    fun solve(ev: Double, s: ExposureSetup): ExposureResult {
        val fixedNd = s.nd
        return when (s.auto) {
            AutoSetting.Iso -> solveIso(ev, s, fixedNd)
            AutoSetting.Shutter -> {
                val nd = fixedNd ?: NdFilter.None
                val t = shutterFor(ev, s.aperture, s.iso, nd.stops)
                ExposureResult(s.aperture, t, s.iso, nd, s.iso, null, null)
            }
            AutoSetting.Aperture -> {
                val nd = fixedNd ?: NdFilter.None
                val n = apertureFor(ev, s.shutter, s.iso, nd.stops)
                ExposureResult(n, s.shutter, s.iso, nd, s.iso, null, null)
            }
        }
    }

    private fun solveIso(ev: Double, s: ExposureSetup, fixedNd: NdFilter?): ExposureResult {
        val lim = s.limits
        // With the filter left to the planner: the weakest one that keeps the ISO from falling below the camera's lowest.
        val nd = fixedNd ?: NdFilter.entries.firstOrNull { isoFor(ev, s.aperture, s.shutter, it.stops) >= lim.isoMin } ?: NdFilter.Nd1000
        val wanted = isoFor(ev, s.aperture, s.shutter, nd.stops)
        val warning = when {
            wanted > lim.isoMax * 1.02 -> ExposureWarning.IsoTooHigh
            wanted < lim.isoMin / 1.02 -> ExposureWarning.IsoTooLow
            else -> null
        }
        var enough = true
        val suggestion = if (warning == ExposureWarning.IsoTooLow) {
            val sufficient = NdFilter.entries.firstOrNull { it.stops > nd.stops && isoFor(ev, s.aperture, s.shutter, it.stops) >= lim.isoMin / 1.02 }
            if (sufficient == null) enough = false
            sufficient ?: NdFilter.Nd1000.takeIf { it.stops > nd.stops }
        } else if (warning == ExposureWarning.IsoTooHigh) {
            NdFilter.entries.lastOrNull { it.stops < nd.stops }
        } else null
        return ExposureResult(s.aperture, s.shutter, wanted.coerceIn(lim.isoMin, lim.isoMax), nd, wanted, warning, suggestion, enough)
    }

    /** An ISO rounded to the nearest third of a stop on the usual scale (100, 125, 160, 200, 250, 320, 400…). */
    fun snapIso(iso: Double): Int {
        val third = (log2(iso / 100.0) * 3).roundToInt()
        val v = 100.0 * 2.0.pow(third / 3.0)
        // The scale cameras print, not the exact powers of the cube root.
        val printed = listOf(100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400)
        return printed.minByOrNull { abs(log2(it / v)) } ?: v.roundToInt()
    }

    /** A shutter time as a camera shows it: 1/250s, 1/2s, 0.8s, 4s, 30s. */
    fun formatShutter(seconds: Double): String {
        val inverse = 1 / seconds
        return when {
            seconds >= 10 -> "${seconds.roundToInt()}s"
            seconds >= 0.95 -> "${((seconds * 10).roundToInt() / 10.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}s"
            // Between a third and one second cameras print 1/2 and 1/3 where it is one, otherwise tenths (0.8s, 0.6s).
            seconds >= 0.3 && abs(inverse - inverse.roundToInt()) < 0.08 -> "1/${inverse.roundToInt()}s"
            seconds >= 0.3 -> "${(seconds * 10).roundToInt() / 10.0}s"
            else -> "1/${niceDenominator(inverse)}s"
        }
    }

    /**
     * The light on the scene in lux for an exposure value at ISO 100, as a light meter states it
     * (calibration constant 250): EV 0 is 2.5 lux, a clear noon about 80 000. For a reading taken off
     * the scene (as the phone's camera takes it) it holds for a mid-grey scene.
     */
    fun luxFromEv(ev: Double): Double = 2.5 * 2.0.pow(ev)

    private fun niceDenominator(d: Double): Int {
        val scale = listOf(2, 3, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000)
        return scale.minByOrNull { abs(log2(it / d)) } ?: d.roundToInt()
    }

    /** An f-number as printed: f/2.8, f/11. */
    fun formatAperture(n: Double): String = "f/" + (if (n >= 10) n.roundToInt().toString() else ((n * 10).roundToInt() / 10.0).toString())
}
