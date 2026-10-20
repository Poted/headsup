package app.headsup.core.fit

import app.headsup.core.track.Metric
import app.headsup.core.track.TelemetrySampler
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reads real watch files that are kept out of the repository (they contain personal data).
 * Run with `HEADSUP_FIT_SAMPLES=/path/to/dir ./gradlew :core:test`; skipped otherwise.
 */
class LocalSamplesTest {
    @Test
    fun `real FIT files decode`() {
        val dir = System.getenv("HEADSUP_FIT_SAMPLES")?.let(::File) ?: return
        val files = dir.listFiles { f -> f.extension.equals("fit", ignoreCase = true) }.orEmpty()
        for (file in files.sortedBy { it.name }) {
            val track = file.inputStream().use { FitActivityReader.read(it) }
            val sampler = TelemetrySampler(track)
            val middle = sampler.frameAt((track.startMs + track.endMs) / 2)
            println(
                "${file.name}: ${track.sportName ?: track.sport} " +
                    "${Instant.ofEpochMilli(track.startMs)}..${Instant.ofEpochMilli(track.endMs)} " +
                    "utcOffset=${track.utcOffsetSeconds}s pauses=${track.pauses.size} zones=${track.hrZoneHighBoundaries} " +
                    "metrics=${track.series.mapValues { it.value.size }} middle=${middle.values}",
            )
            assertTrue(track.has(Metric.HEART_RATE) || track.has(Metric.SPEED), file.name)
        }
    }
}
