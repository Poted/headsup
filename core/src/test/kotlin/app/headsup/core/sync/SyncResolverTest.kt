package app.headsup.core.sync

import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.Sport
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncResolverTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val minute = 60_000L

    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun activity(startIso: String, minutes: Long, offsetSeconds: Int? = 7200): ActivityTrack {
        val start = utc(startIso)
        return ActivityTrack(emptyMap(), start, start + minutes * minute, offsetSeconds, emptyList(), null, Sport.RUNNING, null)
    }

    private fun dji(local: String) =
        TimeEvidence(TimeSource.DJI_FILENAME, ClockReading.Local(LocalDateTime.parse(local)), TimeAnchor.START)

    private fun creationTime(iso: String) =
        TimeEvidence(TimeSource.MP4_CREATION_TIME, ClockReading.LabelledUtc(utc(iso)), TimeAnchor.UNKNOWN)

    @Test
    fun `DJI local file name uses the watch time zone`() {
        val run = activity("2026-10-20T17:00:00Z", 90)
        val clip = ClipInfo(2 * minute, listOf(dji("2026-10-20T19:30:00")))

        val r = assertNotNull(SyncResolver(warsaw).resolve(clip, listOf(run)))

        assertEquals(utc("2026-10-20T17:30:00Z"), r.startUtcMs)
        assertEquals(ZoneReason.ACTIVITY_ZONE, r.primary.zoneReason)
        assertEquals(TimeAnchor.START, r.primary.anchor)
        assertEquals(Confidence.HIGH, r.confidence)
        assertEquals(1.0, r.coverage)
    }

    @Test
    fun `phone abroad does not override the watch zone`() {
        // Ten-hour ultra: the New York interpretation would also land inside the activity.
        val run = activity("2026-10-20T08:00:00Z", 600)
        val clip = ClipInfo(minute, listOf(dji("2026-10-20T14:00:00")))

        val r = assertNotNull(SyncResolver(ZoneId.of("America/New_York")).resolve(clip, listOf(run)))

        assertEquals(utc("2026-10-20T12:00:00Z"), r.startUtcMs)
        assertEquals(Confidence.HIGH, r.confidence)
        assertTrue(r.alternatives >= 1)
    }

    @Test
    fun `half hour zones are supported`() {
        val run = activity("2026-10-20T03:00:00Z", 60, offsetSeconds = 19800) // UTC+5:30
        val clip = ClipInfo(minute, listOf(dji("2026-10-20T08:45:00")))

        val r = assertNotNull(SyncResolver(ZoneId.of("Asia/Kolkata")).resolve(clip, listOf(run)))

        assertEquals(utc("2026-10-20T03:15:00Z"), r.startUtcMs)
        assertEquals(Confidence.HIGH, r.confidence)
    }

    @Test
    fun `local time stored as UTC is detected`() {
        // Camera wrote 19:30 local into creation_time as if it were UTC.
        val run = activity("2026-10-20T17:00:00Z", 60)
        val clip = ClipInfo(minute, listOf(creationTime("2026-10-20T19:30:00Z")))

        val r = assertNotNull(SyncResolver(warsaw).resolve(clip, listOf(run)))

        assertEquals(utc("2026-10-20T17:30:00Z"), r.startUtcMs)
        assertEquals(ZoneReason.ACTIVITY_ZONE, r.primary.zoneReason)
        assertEquals(Confidence.MEDIUM, r.confidence)
    }

    @Test
    fun `creation time written at the end of recording`() {
        // Clip 17:55-18:00 UTC, activity ends 18:02. Read as a start, the clip would overrun by 3 min.
        val run = activity("2026-10-20T17:00:00Z", 62)
        val clip = ClipInfo(5 * minute, listOf(creationTime("2026-10-20T18:00:00Z")))

        val r = assertNotNull(SyncResolver(warsaw).resolve(clip, listOf(run)))

        assertEquals(utc("2026-10-20T17:55:00Z"), r.startUtcMs)
        assertEquals(TimeAnchor.END, r.primary.anchor)
        assertEquals(ZoneReason.AS_UTC, r.primary.zoneReason)
    }

    @Test
    fun `independent sources confirm each other`() {
        val run = activity("2026-10-20T17:00:00Z", 60)
        val clip = ClipInfo(
            3 * minute,
            listOf(
                TimeEvidence(TimeSource.PHONE_FILENAME, ClockReading.Local(LocalDateTime.parse("2026-10-20T19:10:00")), TimeAnchor.START),
                // Phone wrote real UTC at the end of the recording.
                creationTime("2026-10-20T17:13:00.400Z"),
            ),
        )

        val r = assertNotNull(SyncResolver(warsaw).resolve(clip, listOf(run)))

        assertEquals(utc("2026-10-20T17:10:00Z"), r.startUtcMs)
        assertEquals(TimeSource.PHONE_FILENAME, r.primary.evidence.source)
        assertEquals(1, r.agreeing.size)
        assertEquals(TimeAnchor.END, r.agreeing.single().anchor)
        assertEquals(Confidence.HIGH, r.confidence)
    }

    @Test
    fun `clip is matched to the activity that covers it`() {
        val morning = activity("2026-10-20T06:00:00Z", 60)
        val evening = activity("2026-10-20T17:00:00Z", 60)
        val clip = ClipInfo(minute, listOf(dji("2026-10-20T19:20:00")))

        val r = assertNotNull(SyncResolver(warsaw).resolve(clip, listOf(morning, evening)))

        assertEquals(1, r.trackIndex)
        assertEquals(utc("2026-10-20T17:20:00Z"), r.startUtcMs)
    }

    @Test
    fun `no activity anywhere near the clip`() {
        val run = activity("2026-10-20T17:00:00Z", 60)
        val clip = ClipInfo(minute, listOf(dji("2026-10-23T19:20:00")))
        assertNull(SyncResolver(warsaw).resolve(clip, listOf(run)))
    }

    @Test
    fun `no evidence or no activities gives no result`() {
        val run = activity("2026-10-20T17:00:00Z", 60)
        assertNull(SyncResolver(warsaw).resolve(ClipInfo(minute, emptyList()), listOf(run)))
        assertNull(SyncResolver(warsaw).resolve(ClipInfo(minute, listOf(dji("2026-10-20T19:20:00"))), emptyList()))
    }

    @Test
    fun `only an arbitrary zone shift fits so confidence is low`() {
        val run = activity("2026-10-20T10:00:00Z", 30)
        val clip = ClipInfo(minute, listOf(dji("2026-10-20T19:10:00")))

        val r = assertNotNull(SyncResolver(warsaw).resolve(clip, listOf(run)))

        // Several searched offsets fit equally well; none of them is trustworthy.
        assertEquals(ZoneReason.SEARCHED, r.primary.zoneReason)
        assertEquals(Confidence.LOW, r.confidence)
    }

    @Test
    fun `without a zone in the FIT the phone zone is used with daylight saving rules`() {
        // January in Warsaw is UTC+1 even if the phone is used in summer.
        val run = activity("2026-01-15T10:00:00Z", 60, offsetSeconds = null)
        val clip = ClipInfo(minute, listOf(dji("2026-01-15T11:20:00")))

        val r = assertNotNull(SyncResolver(warsaw).resolve(clip, listOf(run)))

        assertEquals(utc("2026-01-15T10:20:00Z"), r.startUtcMs)
        assertEquals(ZoneReason.DEVICE_ZONE, r.primary.zoneReason)
        assertEquals(Confidence.MEDIUM, r.confidence)
    }

    @Test
    fun `clip running a few seconds past the end of the activity still fits`() {
        val run = activity("2026-10-20T17:00:00Z", 30)
        val clip = ClipInfo(63_000, listOf(dji("2026-10-20T19:29:00")))

        val r = assertNotNull(SyncResolver(warsaw).resolve(clip, listOf(run)))

        assertEquals(utc("2026-10-20T17:29:00Z"), r.startUtcMs)
        assertEquals(Confidence.HIGH, r.confidence)
        assertTrue(r.coverage < 1.0)
    }

    @Test
    fun `clip longer than any activity is reported with low confidence`() {
        val run = activity("2026-10-20T17:00:00Z", 30)
        val clip = ClipInfo(40 * minute, listOf(dji("2026-10-20T19:25:00")))

        val r = assertNotNull(SyncResolver(warsaw).resolve(clip, listOf(run)))

        assertEquals(Confidence.LOW, r.confidence)
        assertTrue(r.coverage > 0.0)
    }
}
