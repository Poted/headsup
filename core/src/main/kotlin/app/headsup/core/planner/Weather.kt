package app.headsup.core.planner

import java.time.Instant
import java.util.Locale

/** Cloud cover hour by hour: moments (milliseconds since 1970) with the part of the sky covered (0 clear … 1 overcast). */
class CloudForecast(val hours: List<Pair<Long, Double>>) {
    val isEmpty: Boolean get() = hours.isEmpty()

    /** True when the forecast reaches over some of the stretch from [from] to [to]. */
    fun covers(from: Long, to: Long): Boolean = hours.isNotEmpty() && hours.first().first <= to && hours.last().first >= from

    /** The cover at [millis], moved smoothly between the hours; the first or last hour's outside the forecast. */
    fun at(millis: Long): Double {
        if (hours.isEmpty()) return 0.0
        if (millis <= hours.first().first) return hours.first().second
        if (millis >= hours.last().first) return hours.last().second
        val i = hours.indexOfLast { it.first <= millis }
        val (t0, c0) = hours[i]
        val (t1, c1) = hours[i + 1]
        return c0 + (c1 - c0) * (millis - t0).toDouble() / (t1 - t0)
    }
}

/**
 * Asking MET Norway (the Norwegian Meteorological Institute, api.met.no) for the cloud cover of a
 * place: its Locationforecast, worldwide, about nine days ahead (hourly at first, then every six
 * hours). Its data may be used commercially with credit ("Data from MET Norway"); it asks every
 * client to name itself in the User-Agent and to keep answers until they expire.
 */
object MetNorway {
    /** The address for a place (coordinates to four decimals, as the service asks). */
    fun url(lat: Double, lon: Double): String =
        String.format(Locale.US, "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon)

    /** The credit to show where its forecast is used. */
    const val CREDIT = "Weather: MET Norway"

    // Every brace and bracket is escaped: Android's regular expressions (ICU) reject a lone "}" that the JVM accepts.
    internal val STEP_PATTERN =
        "\"time\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"data\"\\s*:\\s*\\{\\s*\"instant\"\\s*:\\s*\\{\\s*\"details\"\\s*:\\s*\\{([^\\}]*)\\}"
    internal const val CLOUD_PATTERN = "\"cloud_area_fraction\"\\s*:\\s*(-?[0-9.]+)"
    private val step by lazy { Regex(STEP_PATTERN) }
    private val cloud by lazy { Regex(CLOUD_PATTERN) }

    /** Reads the answer: each moment's cloud_area_fraction (%); null if there is none. */
    fun parse(json: String): CloudForecast? {
        val out = ArrayList<Pair<Long, Double>>()
        for (m in step.findAll(json)) {
            val t = runCatching { Instant.parse(m.groupValues[1]).toEpochMilli() }.getOrNull() ?: continue
            val c = cloud.find(m.groupValues[2])?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            out += t to (c / 100.0).coerceIn(0.0, 1.0)
        }
        return if (out.isEmpty()) null else CloudForecast(out.sortedBy { it.first })
    }
}
