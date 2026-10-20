package app.headsup.core.fit

import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.Metric
import app.headsup.core.track.Series
import app.headsup.core.track.Sport
import app.headsup.core.track.TimeRange
import com.garmin.fit.ActivityMesg
import com.garmin.fit.Decode
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.MesgListener
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.TimeInZoneMesg
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import com.garmin.fit.Sport as FitSport

class FitFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Reads a Garmin activity file with the official FIT SDK. */
object FitActivityReader {
    private const val SEMICIRCLES_TO_DEGREES = 180.0 / 2147483648.0

    /** Hysteresis for ascent/descent so barometer noise does not accumulate. */
    internal const val CLIMB_THRESHOLD_M = 2.0

    /** Horizontal distance over which grade is measured when the watch does not record it. */
    internal const val GRADE_WINDOW_M = 20.0
    private const val GRADE_MAX_WINDOW_MS = 120_000L

    fun read(input: InputStream): ActivityTrack {
        val records = mutableListOf<RecordMesg>()
        val events = mutableListOf<EventMesg>()
        val zones = mutableListOf<TimeInZoneMesg>()
        var activity: ActivityMesg? = null
        var session: SessionMesg? = null

        try {
            Decode().read(input, MesgListener { mesg ->
                when (mesg.num) {
                    MesgNum.RECORD -> records += RecordMesg(mesg)
                    MesgNum.EVENT -> events += EventMesg(mesg)
                    MesgNum.TIME_IN_ZONE -> zones += TimeInZoneMesg(mesg)
                    MesgNum.ACTIVITY -> activity = ActivityMesg(mesg)
                    MesgNum.SESSION -> if (session == null) session = SessionMesg(mesg)
                }
            })
        } catch (e: FitRuntimeException) {
            // A watch that ran out of battery leaves a truncated file; keep what was decoded.
            if (records.isEmpty()) throw FitFormatException("Not a readable FIT activity file", e)
        }

        val samples = records
            .mapNotNull { r -> r.timestamp?.date?.time?.let { it to r } }
            .sortedBy { it.first }
            .distinctBy { it.first }
        if (samples.isEmpty()) throw FitFormatException("FIT file contains no record messages")

        val sport = session?.sport.toSport()
        val builders = Metric.entries.associateWith { SeriesBuilder() }
        for ((t, r) in samples) {
            r.heartRate?.toDouble()?.takeIf { it in 20.0..250.0 }?.let { builders.getValue(Metric.HEART_RATE).add(t, it) }
            (r.enhancedAltitude ?: r.altitude)?.toDouble()?.takeIf { it in -500.0..9000.0 }
                ?.let { builders.getValue(Metric.ALTITUDE).add(t, it) }
            r.distance?.toDouble()?.takeIf { it >= 0.0 }?.let { builders.getValue(Metric.DISTANCE).add(t, it) }
            (r.enhancedSpeed ?: r.speed)?.toDouble()?.takeIf { it in 0.0..100.0 }
                ?.let { builders.getValue(Metric.SPEED).add(t, it) }
            val lat = r.positionLat?.let { it * SEMICIRCLES_TO_DEGREES }
            val lon = r.positionLong?.let { it * SEMICIRCLES_TO_DEGREES }
            if (lat != null && lon != null && abs(lat) <= 90.0 && abs(lon) < 180.0) {
                builders.getValue(Metric.LATITUDE).add(t, lat)
                builders.getValue(Metric.LONGITUDE).add(t, lon)
            }
            r.cadence?.toDouble()?.takeIf { it < 255.0 }?.let { c ->
                val perLeg = c + (r.fractionalCadence?.toDouble() ?: 0.0)
                builders.getValue(Metric.CADENCE).add(t, if (sport.doublesCadence) perLeg * 2 else perLeg)
            }
            r.power?.toDouble()?.takeIf { it < 65535.0 }?.let { builders.getValue(Metric.POWER).add(t, it) }
            r.temperature?.toDouble()?.takeIf { it in -60.0..80.0 }?.let { builders.getValue(Metric.TEMPERATURE).add(t, it) }
            r.grade?.toDouble()?.takeIf { abs(it) <= 100.0 }?.let { builders.getValue(Metric.GRADE).add(t, it) }
        }

        val series = builders.mapValues { it.value.build() }.toMutableMap()
        series.getValue(Metric.ALTITUDE).let { altitude ->
            val (ascent, descent) = climbTotals(altitude)
            series[Metric.ASCENT] = ascent
            series[Metric.DESCENT] = descent
        }
        if (series.getValue(Metric.GRADE).isEmpty) {
            series[Metric.GRADE] = derivedGrade(samples.map { it.first }, builders)
        }

        return ActivityTrack(
            series = series.filterValues { !it.isEmpty },
            startMs = samples.first().first,
            endMs = samples.last().first,
            utcOffsetSeconds = activity?.utcOffsetSeconds(),
            pauses = timerPauses(events),
            hrZoneHighBoundaries = hrZones(zones),
            sport = sport,
            sportName = session?.sportProfileName,
        )
    }

    private fun ActivityMesg.utcOffsetSeconds(): Int? {
        val local = localTimestamp ?: return null
        val utc = timestamp?.timestamp ?: return null
        val offset = local - utc
        if (abs(offset) > 14 * 3600) return null
        // Offsets are whole quarter hours; round away any clock jitter between the two fields.
        return ((offset / 900.0).roundToInt() * 900)
    }

    private fun timerPauses(events: List<EventMesg>): List<TimeRange> {
        val pauses = mutableListOf<TimeRange>()
        var pausedAt: Long? = null
        for (e in events.sortedBy { it.timestamp?.date?.time ?: Long.MAX_VALUE }) {
            if (e.event != Event.TIMER) continue
            val t = e.timestamp?.date?.time ?: continue
            when (e.eventType) {
                EventType.STOP, EventType.STOP_ALL, EventType.STOP_DISABLE, EventType.STOP_DISABLE_ALL ->
                    if (pausedAt == null) pausedAt = t
                EventType.START -> pausedAt?.let { start ->
                    if (t > start) pauses += TimeRange(start, t)
                    pausedAt = null
                }
                else -> Unit
            }
        }
        return pauses
    }

    private fun hrZones(zones: List<TimeInZoneMesg>): List<Int>? {
        val mesg = zones.firstOrNull { it.referenceMesg == MesgNum.SESSION } ?: zones.firstOrNull() ?: return null
        val bounds = mesg.hrZoneHighBoundary?.mapNotNull { it?.toInt() }?.filter { it in 1..254 } ?: return null
        return bounds.takeIf { it.size >= 2 && it.zipWithNext().all { (a, b) -> a < b } }
    }

    private fun FitSport?.toSport() = when (this) {
        FitSport.RUNNING -> Sport.RUNNING
        FitSport.CYCLING -> Sport.CYCLING
        FitSport.WALKING -> Sport.WALKING
        FitSport.HIKING -> Sport.HIKING
        else -> Sport.OTHER
    }

    internal fun climbTotals(altitude: Series): Pair<Series, Series> {
        val ascent = DoubleArray(altitude.size)
        val descent = DoubleArray(altitude.size)
        if (altitude.isEmpty) return Series(longArrayOf(), ascent) to Series(longArrayOf(), descent)
        var up = 0.0
        var down = 0.0
        var reference = altitude.values[0]
        for (i in 0 until altitude.size) {
            val a = altitude.values[i]
            if (a - reference >= CLIMB_THRESHOLD_M) {
                up += a - reference; reference = a
            } else if (reference - a >= CLIMB_THRESHOLD_M) {
                down += reference - a; reference = a
            }
            ascent[i] = up
            descent[i] = down
        }
        return Series(altitude.timesMs, ascent) to Series(altitude.timesMs.copyOf(), descent)
    }

    private fun derivedGrade(times: List<Long>, builders: Map<Metric, SeriesBuilder>): Series {
        val altitude = builders.getValue(Metric.ALTITUDE).asMap()
        val distance = builders.getValue(Metric.DISTANCE).asMap()
        val points = times.mapNotNull { t ->
            val a = altitude[t] ?: return@mapNotNull null
            val d = distance[t] ?: return@mapNotNull null
            Triple(t, a, d)
        }
        val out = SeriesBuilder()
        var j = 0
        for (i in points.indices) {
            val (t, a, d) = points[i]
            // Advance the window start while the remaining span is still at least GRADE_WINDOW_M.
            while (j + 1 < i && d - points[j + 1].third >= GRADE_WINDOW_M) j++
            val (tj, aj, dj) = points[j]
            val run = d - dj
            if (run >= GRADE_WINDOW_M && t - tj <= GRADE_MAX_WINDOW_MS) {
                out.add(t, ((a - aj) / run * 100.0).coerceIn(-50.0, 50.0))
            }
        }
        return out.build()
    }

    private class SeriesBuilder {
        private val times = ArrayList<Long>()
        private val values = ArrayList<Double>()

        fun add(t: Long, v: Double) {
            times += t; values += v
        }

        fun asMap(): Map<Long, Double> = times.indices.associate { times[it] to values[it] }
        fun build() = Series(times.toLongArray(), values.toDoubleArray())
    }
}
