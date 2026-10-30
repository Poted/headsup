package app.headsup.media

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.headsup.core.color.GrayFrame
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Frames of a clip for the mask trackers: the video is decoded once in order (fast, unlike
 * seeking for every frame), and from every [stepMs] a frame is kept, scaled so its long side is
 * [longSide] and turned upright as the clip is shown: only its brightness ([read]) or in colour
 * for the AI tracker ([forEachPicture]).
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
        val frames = ArrayList<Pair<Long, GrayFrame>>()
        decode(context, uri, fromMs, toMs, stepMs, onProgress) { ms, image, rotation ->
            frames += ms to luma(image, rotation, longSide)
            true
        }
        return frames
    }

    /**
     * Each [stepMs] of the clip from [fromMs] to [toMs] as a colour picture (long side [longSide],
     * upright), handed to [onFrame] as it is decoded (so only one is held at a time); [onFrame]
     * returns false to stop.
     */
    suspend fun forEachPicture(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        stepMs: Long,
        longSide: Int,
        onProgress: (Float) -> Unit = {},
        onFrame: suspend (ms: Long, picture: Bitmap) -> Boolean,
    ) {
        decode(context, uri, fromMs, toMs, stepMs, onProgress) { ms, image, rotation -> onFrame(ms, colour(image, rotation, longSide)) }
    }

    /**
     * Decodes the clip once in order and hands over the frame nearest each [stepMs] (as the
     * decoder's image, valid only during the call); [take] returns false to stop.
     */
    private suspend fun decode(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        stepMs: Long,
        onProgress: (Float) -> Unit,
        take: suspend (ms: Long, image: Image, rotation: Int) -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return
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
                var more = true
                if (ms >= nextMs && ms <= toMs) {
                    codec.getOutputImage(outIndex)?.use { image -> more = take(ms, image, rotation) }
                    nextMs = ms + stepMs
                    onProgress(((ms - fromMs).toFloat() / (toMs - fromMs).coerceAtLeast(1)).coerceIn(0f, 1f))
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (end || ms > toMs || !more) break
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** The frame in colour (BT.709, limited range), sampled down and rotated upright (8- or 10-bit). */
    private fun colour(image: Image, rotation: Int, longSide: Int): Bitmap {
        val crop = image.cropRect
        val (yp, up, vp) = image.planes
        val wide = yp.pixelStride == 2
        val codedW = crop.width()
        val codedH = crop.height()
        val turned = rotation == 90 || rotation == 270
        val shownW = if (turned) codedH else codedW
        val shownH = if (turned) codedW else codedH
        val scale = minOf(1f, longSide.toFloat() / maxOf(shownW, shownH))
        val w = (shownW * scale).toInt().coerceAtLeast(8)
        val h = (shownH * scale).toInt().coerceAtLeast(8)
        val pixels = IntArray(w * h)
        // 10-bit samples are 16-bit with the value in the high bits: their high byte will do.
        val hi = if (wide) 1 else 0
        for (y in 0 until h) for (x in 0 until w) {
            val sx = (x / scale).toInt().coerceIn(0, shownW - 1)
            val sy = (y / scale).toInt().coerceIn(0, shownH - 1)
            val (cx, cy) = when (rotation) {
                90 -> sy to (codedH - 1 - sx)
                180 -> (codedW - 1 - sx) to (codedH - 1 - sy)
                270 -> (codedW - 1 - sy) to sx
                else -> sx to sy
            }
            val px = crop.left + cx
            val py = crop.top + cy
            val luma = (yp.buffer.get(py * yp.rowStride + px * yp.pixelStride + hi).toInt() and 0xFF) - 16
            val cpos = (py / 2) * up.rowStride + (px / 2) * up.pixelStride + hi
            val u = (up.buffer.get(cpos).toInt() and 0xFF) - 128
            val v = (vp.buffer.get((py / 2) * vp.rowStride + (px / 2) * vp.pixelStride + hi).toInt() and 0xFF) - 128
            val l = 1.164f * luma
            val r = (l + 1.793f * v).toInt().coerceIn(0, 255)
            val g = (l - 0.213f * u - 0.533f * v).toInt().coerceIn(0, 255)
            val b = (l + 2.112f * u).toInt().coerceIn(0, 255)
            pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The Y plane, sampled down and rotated upright (8- or 10-bit). */
    private fun luma(image: Image, rotation: Int, longSide: Int): GrayFrame {
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
