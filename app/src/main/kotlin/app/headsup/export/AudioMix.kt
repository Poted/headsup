package app.headsup.export

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A music track under the whole timeline, mixed with the clips' own sound. */
data class MusicTrack(
    val uri: Uri,
    val name: String,
    /** Music level, 0..1. */
    val volume: Float = 0.8f,
    /** Level of the clips' own sound under the music, 0..1. */
    val originalVolume: Float = 0.4f,
)

@OptIn(UnstableApi::class)
object AudioMix {
    /** Scales the sound by [gain] (mono and stereo). */
    fun gain(gain: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        for (channels in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, channels).scaleBy(gain))
    }
}

/**
 * Fades 16-bit PCM in over [fadeInUs] and out over the last [fadeOutUs] of [totalUs], e.g. music
 * that would otherwise stop abruptly where the timeline ends.
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
