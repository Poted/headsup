package app.headsup.core.planner

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Cloud cover hour by hour: moments (milliseconds since 1970) with the part of the sky covered (0 clear … 1 overcast). */
class CloudForecast(val hours: List<Pair<Long, Double>>) {
    val isEmpty: Boolean get() = hours.isEmpty()

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

/** Asking a free weather service (Open-Meteo, no key) for the cloud cover of a place and reading its answer. */
object OpenMeteo {
    /** The address asking for the hourly cloud cover of [date] and the days either side (the place's zone may spill over), in UTC. */
    fun url(lat: Double, lon: Double, date: LocalDate): String =
        String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&hourly=cloud_cover&timezone=GMT&start_date=%s&end_date=%s",
            lat, lon, date.minusDays(1), date.plusDays(1),
        )

    /** Reads the answer; null if it holds no hours. Hours with no value are left out. */
    fun parse(json: String): CloudForecast? {
        val times = Regex("\"time\"\\s*:\\s*\\[(.*?)]").find(json)?.groupValues?.get(1) ?: return null
        val values = Regex("\"cloud_cover\"\\s*:\\s*\\[(.*?)]").find(json)?.groupValues?.get(1) ?: return null
        val ts = times.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        val vs = values.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ts.size != vs.size) return null
        val out = ArrayList<Pair<Long, Double>>()
        for (i in ts.indices) {
            val v = vs[i].toDoubleOrNull() ?: continue
            val t = runCatching { LocalDateTime.parse(ts[i]).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: continue
            out += t to (v / 100.0).coerceIn(0.0, 1.0)
        }
        return if (out.isEmpty()) null else CloudForecast(out.sortedBy { it.first })
    }
}
