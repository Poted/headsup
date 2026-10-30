package app.headsup.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.headsup.core.color.GrayFrame
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Small grey frames of a clip for the mask tracker: the video is decoded once in order (fast,
 * unlike seeking for every frame), and from every [stepMs] only the brightness is kept, scaled so
 * its long side is [longSide] and turned upright as the clip is shown.
 */
object TrackingFrames {
    suspend fun read(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        stepMs: Long,
        longSide: Int = 400,
        onProgress: (Float) -> Unit = {},
    ): List<Pair<Long, GrayFrame>> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val frames = ArrayList<Pair<Long, GrayFrame>>()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return frames
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, null, null, 0)
                start()
            }
            extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var nextMs = fromMs
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (size < 0 || extractor.sampleTime > (toMs + 500) * 1000) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex < 0) continue
                val ms = info.presentationTimeUs / 1000
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (ms >= nextMs && ms <= toMs) {
                    codec.getOutputImage(outIndex)?.use { image -> frames += ms to luma(image, rotation, longSide) }
                    nextMs = ms + stepMs
                    onProgress(((ms - fromMs).toFloat() / (toMs - fromMs).coerceAtLeast(1)).coerceIn(0f, 1f))
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (end || ms > toMs) break
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
        return frames
    }

    /** The Y plane, sampled down and rotated upright (8- or 10-bit). */
    private fun luma(image: android.media.Image, rotation: Int, longSide: Int): GrayFrame {
        val crop = image.cropRect
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val codedW = crop.width()
        val codedH = crop.height()
        val turned = rotation == 90 || rotation == 270
        val shownW = if (turned) codedH else codedW
        val shownH = if (turned) codedW else codedH
        val scale = longSide.toFloat() / maxOf(shownW, shownH)
        val w = (shownW * scale).toInt().coerceAtLeast(8)
        val h = (shownH * scale).toInt().coerceAtLeast(8)
        val pixels = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            // Position in the upright picture, then in the coded one.
            val sx = (x / scale).toInt().coerceIn(0, shownW - 1)
            val sy = (y / scale).toInt().coerceIn(0, shownH - 1)
            val (cx, cy) = when (rotation) {
                90 -> sy to (codedH - 1 - sx)
                180 -> (codedW - 1 - sx) to (codedH - 1 - sy)
                270 -> (codedW - 1 - sy) to sx
                else -> sx to sy
            }
            val pos = (crop.top + cy) * rowStride + (crop.left + cx) * pixelStride
            // 10-bit samples are 16-bit with the value in the high bits: their high byte will do.
            val v = buffer.get(if (pixelStride == 2) pos + 1 else pos).toInt() and 0xFF
            pixels[y * w + x] = v / 255f
        }
        return GrayFrame(w, h, pixels)
    }
}
