package app.headsup.core.fit

import com.garmin.fit.ActivityMesg
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.Manufacturer
import com.garmin.fit.Mesg
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.TimeInZoneMesg
import java.io.ByteArrayInputStream
import java.time.Instant

/** Writes synthetic activity files with the FIT SDK encoder, so tests control every field. */
class TestFit(startMs: Long) {
    private val mesgs = mutableListOf<Mesg>(
        FileIdMesg().apply {
            type = File.ACTIVITY
            manufacturer = Manufacturer.GARMIN
            timeCreated = time(startMs)
        },
    )

    fun record(
        utcMs: Long,
        hr: Int? = null,
        altitude: Double? = null,
        distance: Double? = null,
        speed: Double? = null,
        lat: Double? = null,
        lon: Double? = null,
        cadence: Int? = null,
        fractionalCadence: Double? = null,
    ) {
        mesgs += RecordMesg().apply {
            timestamp = time(utcMs)
            hr?.let { heartRate = it.toShort() }
            altitude?.let { enhancedAltitude = it.toFloat() }
            distance?.let { this.distance = it.toFloat() }
            speed?.let { enhancedSpeed = it.toFloat() }
            lat?.let { positionLat = (it / 180.0 * 2147483648.0).toInt() }
            lon?.let { positionLong = (it / 180.0 * 2147483648.0).toInt() }
            cadence?.let { this.cadence = it.toShort() }
            fractionalCadence?.let { this.fractionalCadence = it.toFloat() }
        }
    }

    fun timer(utcMs: Long, type: EventType) {
        mesgs += EventMesg().apply {
            timestamp = time(utcMs)
            event = Event.TIMER
            eventType = type
        }
    }

    fun session(sport: Sport, name: String? = null) {
        mesgs += SessionMesg().apply {
            this.sport = sport
            name?.let { sportProfileName = it }
        }
    }

    fun hrZones(vararg bounds: Int) {
        mesgs += TimeInZoneMesg().apply {
            referenceMesg = MesgNum.SESSION
            bounds.forEachIndexed { i, b -> setHrZoneHighBoundary(i, b.toShort()) }
        }
    }

    fun activity(utcMs: Long, utcOffsetSeconds: Int) {
        mesgs += ActivityMesg().apply {
            timestamp = time(utcMs)
            localTimestamp = time(utcMs).timestamp + utcOffsetSeconds
        }
    }

    fun bytes(): ByteArray = BufferEncoder(Fit.ProtocolVersion.V2_0).run {
        mesgs.forEach { write(it) }
        close()
    }

    fun read() = FitActivityReader.read(ByteArrayInputStream(bytes()))

    companion object {
        fun time(utcMs: Long) = DateTime(Instant.ofEpochMilli(utcMs))
    }
}
