package app.headsup.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.headsup.core.sync.Mp4DateParser
import java.util.concurrent.ConcurrentHashMap

/**
 * What a video recorded about itself, for texts showing its data without a watch: when it was
 * started (the file's creation time, UTC) and where (the GPS position phones write, ISO 6709).
 */
data class VideoInfo(val startUtcMs: Long?, val latitude: Double?, val longitude: Double?) {
    companion object {
        private val cache = ConcurrentHashMap<Uri, VideoInfo>()
        private val ISO6709 = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")

        fun read(context: Context, uri: Uri): VideoInfo = cache.getOrPut(uri) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, uri)
                    val start = Mp4DateParser.parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                    val (lat, lon) = location(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                    VideoInfo(start, lat, lon)
                } finally {
                    r.release()
                }
            }.getOrNull() ?: VideoInfo(null, null, null)
        }

        /** "+52.2297+021.0122/" (ISO 6709) as latitude and longitude; nulls when absent or 0,0. */
        fun location(raw: String?): Pair<Double?, Double?> {
            val m = raw?.trim()?.let { ISO6709.find(it) } ?: return null to null
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat == null || lon == null || (lat == 0.0 && lon == 0.0) || kotlin.math.abs(lat) > 90 || kotlin.math.abs(lon) > 180) return null to null
            return lat to lon
        }
    }
}
