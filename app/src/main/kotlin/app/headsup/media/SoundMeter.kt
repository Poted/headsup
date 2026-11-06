package app.headsup.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.headsup.core.audio.Loudness
import java.nio.ByteOrder

/** The loudness of a file's sound, decoded and measured ([Loudness]). */
object SoundMeter {
    /**
     * [uri]'s sound from [fromMs] to [toMs] (file time) measured; null when it has no sound or
     * it cannot be decoded. [stopped] is asked now and then.
     */
    fun measure(context: Context, uri: Uri, fromMs: Long, toMs: Long, stopped: () -> Boolean = { false }): Loudness.Measure? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(fromMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            var meter: Loudness.Meter? = null
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var buffer = ShortArray(0)
            while (!stopped()) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val input = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(input, 0)
                        if (n < 0 || extractor.sampleTime > toMs * 1000L) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    meter = null
                } else if (o >= 0) {
                    if (info.size > 0 && info.presentationTimeUs >= fromMs * 1000L && info.presentationTimeUs <= toMs * 1000L) {
                        val out = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val shorts = info.size / 2
                        if (buffer.size < shorts) buffer = ShortArray(shorts)
                        out.asShortBuffer().get(buffer, 0, shorts)
                        // Up to two channels count (surround's others are rarely what is heard on a phone).
                        val m = meter ?: Loudness.Meter(rate, minOf(channels, 2)).also { meter = it }
                        if (channels <= 2) m.add(buffer, shorts) else {
                            val frames = shorts / channels
                            val two = ShortArray(frames * 2) { k -> buffer[(k / 2) * channels + k % 2] }
                            m.add(two)
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (stopped()) return null
            return meter?.result()
        } catch (e: Throwable) {
            e.printStackTrace()
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
