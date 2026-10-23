package app.headsup.export

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import app.headsup.core.edit.AudioPiece
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A sound file in the project's library (copied into the app's folder), ready to place. */
data class AudioFile(val uri: Uri, val name: String, val durationMs: Long = 0L)

/**
 * A clip on an audio track: a stretch of [uri] placed on the timeline (see [AudioPiece]) at its
 * own level. Tracks ([lane]) play together, so music, ambience and effects can overlap.
 */
data class AudioClip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val lane: Int = 0,
    val sourceDurationMs: Long = 0L,
    val startOnTimelineMs: Long = 0L,
    val sourceStartMs: Long = 0L,
    /** Where the used part ends in the source (0 = its end). */
    val sourceEndMs: Long = 0L,
    /** Level, 0..1. */
    val volume: Float = 0.8f,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
) {
    val piece: AudioPiece get() = AudioPiece(sourceDurationMs, startOnTimelineMs, sourceStartMs, sourceEndMs, fadeInMs, fadeOutMs)

    fun with(p: AudioPiece) = copy(
        startOnTimelineMs = p.startOnTimelineMs, sourceStartMs = p.sourceStartMs, sourceEndMs = p.sourceEndMs,
        fadeInMs = p.fadeInMs, fadeOutMs = p.fadeOutMs,
    )
}

@OptIn(UnstableApi::class)
object AudioMix {
    /** Scales the sound by [gain] (mono and stereo). */
    fun gain(gain: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        for (channels in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, channels).scaleBy(gain))
    }
}

/**
 * Fades 16-bit PCM in over [fadeInUs] and out over the last [fadeOutUs] of [totalUs], where the
 * user asked for a fade (an audio clip, or a video clip fading in or out).
 */
@OptIn(UnstableApi::class)
class FadeAudioProcessor(private val totalUs: Long, private val fadeInUs: Long, private val fadeOutUs: Long) : BaseAudioProcessor() {
    private var framesDone = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val channels = format.channelCount
        val frameBytes = 2 * channels
        val frames = inputBuffer.remaining() / frameBytes
        val out = replaceOutputBuffer(frames * frameBytes)
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            val timeUs = (framesDone + f) * 1_000_000L / format.sampleRate
            var g = 1f
            if (fadeInUs > 0 && timeUs < fadeInUs) g = timeUs.toFloat() / fadeInUs
            val left = totalUs - timeUs
            if (fadeOutUs > 0 && left < fadeOutUs) g = minOf(g, (left.coerceAtLeast(0L)).toFloat() / fadeOutUs)
            for (c in 0 until channels) {
                val sample = input.short
                out.putShort((sample * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        framesDone += frames
        out.flip()
    }

    override fun onFlush() {
        framesDone = 0L
    }
}
