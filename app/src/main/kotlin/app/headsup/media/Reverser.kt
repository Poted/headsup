package app.headsup.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Reversed copies of clips: the part of a file that is used, played backwards. Video can only be
 * decoded forwards, so the part is first copied with every frame a key frame (each one complete in
 * itself, at a high bitrate: no visible loss), then those frames are written in the opposite
 * order, untouched. The copy has no sound (sound played backwards is rarely wanted). Copies are
 * kept in the app's files by source and part, so reversing again, or undoing and redoing, is
 * instant.
 */
@OptIn(UnstableApi::class)
object Reverser {
    /** This phone's encoder did not make every frame a key frame, so the frames cannot be reordered. */
    class NotIntraException : Exception("The video encoder could not make a copy with every frame complete")

    private fun dir(context: Context) = File(context.filesDir, "reversed").apply { mkdirs() }

    fun fileFor(context: Context, meta: ClipMetadata, startMs: Long, endMs: Long): File {
        val key = "${meta.uri}|$startMs|$endMs"
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(context), "$hash.mp4")
    }

    /** The reversed copy of [meta]'s [startMs]..[endMs] (made, or found from before); [onProgress] 0..1. */
    suspend fun reverse(context: Context, meta: ClipMetadata, startMs: Long, endMs: Long, onProgress: (Float) -> Unit): File {
        val target = fileFor(context, meta, startMs, endMs)
        if (target.exists()) return target
        val intra = File(target.path + ".intra.mp4")
        val part = File(target.path + ".part")
        try {
            intraCopy(context, meta, startMs, endMs, intra) { onProgress(it * 0.9f) }
            withContext(Dispatchers.IO) {
                writeReversed(intra, part) { onProgress(0.9f + 0.1f * it) }
                if (!part.renameTo(target)) error("Could not keep the reversed copy")
            }
        } finally {
            intra.delete()
            part.delete()
        }
        return target
    }

    /** [meta]'s part copied with every frame a key frame, no sound. Transformer runs on the main thread. */
    private suspend fun intraCopy(context: Context, meta: ClipMetadata, startMs: Long, endMs: Long, out: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val item = MediaItem.Builder()
                    .setUri(meta.uri)
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
                    .build()
                // Phone encoders stop at 4K: larger files are copied at 4K.
                val shortSide = minOf(meta.width, meta.height)
                val effects = if (shortSide > MAX_SHORT_SIDE) Effects(emptyList(), listOf(Presentation.createForShortSide(MAX_SHORT_SIDE))) else Effects.EMPTY
                val edited = EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(effects).build()
                val scale = if (shortSide > MAX_SHORT_SIDE) MAX_SHORT_SIDE.toFloat() / shortSide else 1f
                val pixels = meta.width * scale * meta.height * scale
                val bitrate = (pixels * (meta.frameRate ?: 30f) * BITS_PER_PIXEL).toLong().coerceIn(MIN_BITRATE, MAX_BITRATE).toInt()
                val encoders = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).setiFrameIntervalSeconds(0f).build())
                    .setEnableFallback(true)
                    .build()
                var done = false
                val transformer = Transformer.Builder(context)
                    // HEVC keeps 10-bit and HDR as they are.
                    .setVideoMimeType(MimeTypes.VIDEO_H265)
                    .setEncoderFactory(encoders)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done = true
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            done = true
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer.start(edited, out.absolutePath)
                val watcher = kotlinx.coroutines.MainScope().launch {
                    val holder = ProgressHolder()
                    while (!done) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        delay(300)
                    }
                }
                cont.invokeOnCancellation {
                    watcher.cancel()
                    kotlinx.coroutines.MainScope().launch { if (!done) transformer.cancel() }
                }
            }
        }

    /** The frames of [intra] (all key frames) written to [out] in the opposite order, unchanged. */
    private fun writeReversed(intra: File, out: File, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(intra.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("The copy has no picture")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            // The frames' times, and whether each is complete in itself.
            val times = ArrayList<Long>()
            var largest = 0L
            while (true) {
                val t = extractor.sampleTime
                if (t < 0) break
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) throw NotIntraException()
                times += t
                largest = maxOf(largest, extractor.sampleSize)
                extractor.advance()
            }
            if (times.isEmpty()) error("The copy has no frames")
            times.sort()
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            if (format.containsKey(MediaFormat.KEY_ROTATION)) m.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
            val outTrack = m.addTrack(format)
            m.start()
            val buffer = ByteBuffer.allocateDirect(largest.toInt().coerceAtLeast(1024))
            val info = MediaCodec.BufferInfo()
            val last = times.last()
            for ((n, i) in times.indices.reversed().withIndex()) {
                extractor.seekTo(times[i], MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) error("A frame could not be read")
                info.set(0, size, last - times[i], MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(outTrack, buffer, info)
                if (n % 30 == 0) onProgress(n.toFloat() / times.size)
            }
            m.stop()
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private const val MAX_SHORT_SIDE = 2160
    /** Every frame complete in itself needs about this many bits per pixel to look like the original. */
    private const val BITS_PER_PIXEL = 0.3f
    private const val MIN_BITRATE = 8_000_000L
    private const val MAX_BITRATE = 160_000_000L
}
