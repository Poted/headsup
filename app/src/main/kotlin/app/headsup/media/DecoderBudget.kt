package app.headsup.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import kotlin.math.ceil

/**
 * Whether the phone can decode two clips at the same time. The preview keeps the next clip ready
 * in a second player; with large files (8K) the hardware decoder has room for only one, and the
 * second fails, so then the next clip is loaded without its picture until the cut.
 */
object DecoderBudget {
    private const val UHD_PIXELS = 3840 * 2160

    fun fitsTwo(a: ClipMetadata, b: ClipMetadata): Boolean {
        // Above 4K a hardware decoder is not counted on for two streams, whatever it reports.
        if (a.width.toLong() * a.height > UHD_PIXELS * 11L / 10 || b.width.toLong() * b.height > UHD_PIXELS * 11L / 10) return false
        val mime = a.format?.mime ?: return true
        // Different codecs mostly use different decoders.
        if (b.format?.mime != mime) return true
        return runCatching { capacityCovers(mime, a, b) }.getOrDefault(false)
    }

    private fun macroblocks(m: ClipMetadata) = ceil(maxOf(m.width, m.height) / 16.0) * ceil(minOf(m.width, m.height) / 16.0)

    private fun capacityCovers(mime: String, a: ClipMetadata, b: ClipMetadata): Boolean {
        val big = if (macroblocks(a) >= macroblocks(b)) a else b
        val w = maxOf(big.width, big.height)
        val h = minOf(big.width, big.height)
        // Both streams as one at the larger size: its frame rate carries the combined load.
        val load = macroblocks(a) * (a.frameRate ?: 30f) + macroblocks(b) * (b.frameRate ?: 30f)
        val rate = ceil(load / macroblocks(big)).toInt()
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                info.isHardwareAccelerated
        }
        return decoders.any { info ->
            val caps: MediaCodecInfo.CodecCapabilities = info.getCapabilitiesForType(mime)
            if (caps.maxSupportedInstances < 2) return@any false
            val video = caps.videoCapabilities ?: return@any false
            val points = video.supportedPerformancePoints
            if (!points.isNullOrEmpty()) {
                val need = MediaCodecInfo.VideoCapabilities.PerformancePoint(w, h, rate)
                points.any { it.covers(need) }
            } else {
                video.areSizeAndRateSupported(w, h, rate.toDouble())
            }
        }
    }
}
