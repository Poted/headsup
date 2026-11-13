package app.headsup.core.planner

import kotlin.math.log2
import kotlin.math.pow

/**
 * What the camera may do through a timelapse where the filter cannot be changed: the aperture is
 * held, the shutter may run from [shutterMin] (the shortest, e.g. 1/2 s so the day is blurred) to
 * [shutterMax] (the longest, set by the interval), and the ISO from [isoMin] to [isoMax].
 */
data class TimelapseLimits(
    val aperture: Double = 2.8,
    val shutterMin: Double = 0.5,
    val shutterMax: Double = 4.0,
    val isoMin: Double = 100.0,
    val isoMax: Double = 3200.0,
) {
    /** How many stops of light the shutter and ISO together can cover. */
    val rangeStops: Double get() = log2(shutterMax / shutterMin) + log2(isoMax / isoMin)
}

/** One moment of a timelapse with the filter held: the light, and the shutter and ISO the camera must use. */
data class TimelapseStep(
    val millis: Long,
    val sunAltitude: Double,
    val ev: Double,
    val shutter: Double,
    val iso: Double,
    /** False when the light needs more than the shutter and ISO can give (too bright or too dim at this moment). */
    val inReach: Boolean,
)

/** The answer for a window of time. */
data class TimelapsePlan(
    /** The dimmest and brightest light in the window (EV at ISO 100). */
    val evMin: Double,
    val evMax: Double,
    /** The filter, in stops, that is least able to protect the brightest moment (below it the day overexposes)… */
    val ndLow: Double,
    /** …and the strongest that still lets the dimmest moment be exposed (above it the night is too dark). */
    val ndHigh: Double,
    /** True when one filter serves the whole window. */
    val feasible: Boolean,
    /** The listed filters inside that range (empty if none, or if not feasible). */
    val filters: List<NdFilter>,
    /** The filter the steps are worked with: the listed one nearest the middle of the range, or the nearest one when none fits. */
    val chosen: NdFilter,
    /** How much of the light's change (in stops) the camera cannot cover; 0 when feasible. */
    val shortfallStops: Double,
    val steps: List<TimelapseStep>,
)

/** A timelapse through changing light, with one filter held: which filter, and the shutter and ISO at each moment. */
object Timelapse {
    /** From the light's span alone: the filter range that serves it. */
    fun window(evMin: Double, evMax: Double, l: TimelapseLimits): ClosedFloatingPointRange<Double> {
        val n2 = l.aperture * l.aperture * 100.0
        // The shutter times ISO the scene asks for is 100·N²·2^nd / 2^ev; it must lie in [isoMin·shutterMin, isoMax·shutterMax].
        val low = evMax + log2(l.isoMin * l.shutterMin / n2)
        val high = evMin + log2(l.isoMax * l.shutterMax / n2)
        return low..high
    }

    /**
     * Works a timelapse out from [evs] (moment, EV, sun altitude), with [nd] held, or with the
     * filter chosen when it is null.
     */
    fun plan(samples: List<Triple<Long, Double, Double>>, l: TimelapseLimits, nd: NdFilter? = null): TimelapsePlan {
        require(samples.isNotEmpty())
        val evMin = samples.minOf { it.second }
        val evMax = samples.maxOf { it.second }
        val w = window(evMin, evMax, l)
        val feasible = w.start <= w.endInclusive + 1e-9
        val listed = if (feasible) NdFilter.entries.filter { it.stops >= w.start - 0.025 && it.stops <= w.endInclusive + 0.025 } else emptyList()
        val middle = if (feasible) (w.start + w.endInclusive) / 2 else (w.start + w.endInclusive) / 2
        val chosen = nd ?: (listed.ifEmpty { NdFilter.entries }).minByOrNull { kotlin.math.abs(it.stops - middle) }!!
        val n2 = l.aperture * l.aperture * 100.0
        val steps = samples.map { (ms, ev, alt) ->
            val p = n2 * 2.0.pow(chosen.stops) / 2.0.pow(ev) // shutter × ISO the light asks for
            // Shutter first, at the lowest ISO: the day takes the shortest shutter allowed, the night lengthens it, then the ISO rises.
            val shutter = (p / l.isoMin).coerceIn(l.shutterMin, l.shutterMax)
            val iso = p / shutter
            TimelapseStep(ms, alt, ev, shutter, iso.coerceIn(l.isoMin, l.isoMax), iso >= l.isoMin / 1.02 && iso <= l.isoMax * 1.02)
        }
        val shortfall = if (feasible) 0.0 else w.start - w.endInclusive
        return TimelapsePlan(evMin, evMax, w.start, w.endInclusive, feasible, listed, chosen, shortfall, steps)
    }

    /** The light of a place every [stepMinutes] from [fromMillis] to [toMillis]; with [measured], worked from the camera's reading. */
    fun sample(
        place: Place, conditions: SkyConditions, fromMillis: Long, toMillis: Long, stepMinutes: Int, measured: Measured? = null,
    ): List<Triple<Long, Double, Double>> {
        val out = ArrayList<Triple<Long, Double, Double>>()
        var t = fromMillis
        val step = stepMinutes.coerceAtLeast(1) * 60_000L
        while (t <= toMillis) {
            val ev = if (measured == null) SceneLight.ev(place, t, conditions) else SceneLight.relative(place, measured.atMillis, measured.ev, t, conditions)
            out += Triple(t, ev, Sky.sun(place, t).altitude)
            t += step
        }
        return out
    }
}
