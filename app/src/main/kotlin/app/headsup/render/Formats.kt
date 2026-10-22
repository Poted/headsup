package app.headsup.render

import app.headsup.core.track.Sport
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Value and unit as shown on the dashboard; "--" when unknown. */
data class Reading(val value: String, val unit: String)

object Formats {
    const val UNKNOWN = "--"
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun heartRate(bpm: Double?) = Reading(bpm?.roundToInt()?.toString() ?: UNKNOWN, "bpm")

    fun speed(mps: Double?) = Reading(mps?.let { String.format(Locale.US, "%.1f", it * 3.6) } ?: UNKNOWN, "km/h")

    /** Pace is meaningless when standing still, so very low speeds show as unknown. */
    fun pace(mps: Double?): Reading {
        if (mps == null || mps < 0.5) return Reading(UNKNOWN, "/km")
        val secondsPerKm = (1000.0 / mps).roundToLong()
        if (secondsPerKm >= 60 * 60) return Reading(UNKNOWN, "/km")
        return Reading(String.format(Locale.US, "%d:%02d", secondsPerKm / 60, secondsPerKm % 60), "/km")
    }

    fun altitude(m: Double?) = Reading(m?.roundToInt()?.toString() ?: UNKNOWN, "m")

    fun distance(m: Double?) = Reading(m?.let { String.format(Locale.US, "%.2f", it / 1000.0) } ?: UNKNOWN, "km")

    fun ascent(m: Double?) = Reading(m?.roundToInt()?.toString() ?: UNKNOWN, "m")

    fun grade(percent: Double?) = Reading(
        percent?.let { String.format(Locale.US, "%.1f", if (abs(it) < 0.05) 0.0 else it) } ?: UNKNOWN,
        "%",
    )

    fun cadence(value: Double?, sport: Sport) =
        Reading(value?.roundToInt()?.toString() ?: UNKNOWN, if (sport.doublesCadence) "spm" else "rpm")

    fun power(w: Double?) = Reading(w?.roundToInt()?.toString() ?: UNKNOWN, "W")

    fun temperature(c: Double?) = Reading(c?.roundToInt()?.toString() ?: UNKNOWN, "°C")

    fun clock(utcMs: Long, offsetSeconds: Int): String =
        clock.format(Instant.ofEpochMilli(utcMs).atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds)))

    fun date(utcMs: Long, offsetSeconds: Int): String =
        date.format(Instant.ofEpochMilli(utcMs).atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds)))

    /** "UTC+2", "UTC+5:30", "UTC-3". */
    fun utcOffset(seconds: Int): String {
        val sign = if (seconds < 0) "-" else "+"
        val h = abs(seconds) / 3600
        val m = abs(seconds) % 3600 / 60
        return if (m == 0) "UTC$sign$h" else String.format(Locale.US, "UTC%s%d:%02d", sign, h, m)
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
