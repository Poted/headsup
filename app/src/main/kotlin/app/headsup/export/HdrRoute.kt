package app.headsup.export

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Clock
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultCodec
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory

/**
 * 10-bit export through Media3's HDR route.
 *
 * Media3 keeps 10 bits (16-bit float textures, Main10 encoding) only for HDR input, and D-Log M
 * is tagged SDR. So the clips' video is labelled HLG/BT.2020 on the way in ([assetLoaderFactory]);
 * Media3 then turns the samples into "linear BT.2020 light", which [GradeEffect] undoes exactly
 * ([HLG_FIX] and the HLG OETF) to get back the camera's own values. After colour and texture,
 * [HdrOutputEffect] places the finished SDR picture in an HLG signal as BT.2408 recommends (SDR
 * white at 75 % HLG), and the encoder writes HEVC Main10 HLG, like DJI Mimo's 10-bit exports.
 */
@OptIn(UnstableApi::class)
object HdrRoute {
    /** Scene-linear value Media3 encodes as 75 % HLG: where SDR white sits in HLG footage (BT.2408). */
    val SDR_WHITE: Float = hlgEotf(0.75f)

    /**
     * HLG signal levels offered for the white of an HLG export. 75 % is the broadcast level (right
     * on HDR screens); phone galleries and DJI Mimo show it much darker than SDR, higher levels less so.
     */
    const val DEFAULT_WHITE = 0.75f
    val WHITE_LEVELS: List<Pair<Float, String>> = listOf(DEFAULT_WHITE to "Standard (75 %)", 0.85f to "85 %", 0.93f to "93 %")

    /** Scene-linear light for an HLG signal level [x] (Media3's HLG EOTF). */
    fun hlgEotf(x: Float): Float {
        val a = 0.17883277f
        val b = 0.28466892f
        val c = 0.55991073f
        return if (x <= 0.5f) x * x / 3f else (b + kotlin.math.exp((x - c) / a)) / 12f
    }

    /** Y'CbCr→RGB, limited range, as rows (R, G, B) of (Y, Cb, Cr); BT.2020 as in Media3, BT.709 standard. */
    private val BT2020 = arrayOf(floatArrayOf(1.1689f, 0f, 1.6853f), floatArrayOf(1.1689f, -0.1881f, -0.6530f), floatArrayOf(1.1689f, 2.1502f, 0f))
    private val BT709 = arrayOf(floatArrayOf(1.16438f, 0f, 1.79274f), floatArrayOf(1.16438f, -0.21325f, -0.53291f), floatArrayOf(1.16438f, 2.11240f, 0f))

    /**
     * Turns RGB that Media3 computed with the BT.2020 matrix back into what the BT.709 matrix
     * (the clip's real one) gives: BT709 · BT2020⁻¹, column-major for GLSL.
     */
    val HLG_FIX: FloatArray by lazy {
        val m = multiply(BT709, invert(BT2020))
        FloatArray(9) { i -> m[i % 3][i / 3] }
    }

    private fun multiply(a: Array<FloatArray>, b: Array<FloatArray>) =
        Array(3) { r -> FloatArray(3) { c -> (0..2).sumOf { (a[r][it] * b[it][c]).toDouble() }.toFloat() } }

    private fun invert(m: Array<FloatArray>): Array<FloatArray> {
        val (a, b, c) = m[0]
        val (d, e, f) = m[1]
        val (g, h, i) = m[2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return arrayOf(
            floatArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
            floatArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
            floatArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
        )
    }

    /** Whether an HEVC encoder here can take Media3's HDR route (Android 13+, HDR editing). */
    val deviceSupported: Boolean by lazy {
        android.os.Build.VERSION.SDK_INT >= 33 &&
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && MimeTypes.VIDEO_H265 in info.supportedTypes &&
                    info.getCapabilitiesForType(MimeTypes.VIDEO_H265)
                        .isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)
            }
    }

    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** How a clip enters the route, from its own colour tags. */
    fun inputOf(transfer: String?, primaries: String?): HdrInput = when {
        transfer == "HLG" -> HdrInput.HLG
        primaries == "BT.2020" -> HdrInput.RELABELLED_BT2020
        else -> HdrInput.RELABELLED_BT709
    }

    /**
     * Encoders for the 10-bit SDR mode: Media3 asks for HLG (its 10-bit route), but the HEVC encoder
     * is configured as Main10 BT.709 SDR, so the file is tagged like DJI Mimo's 10-bit exports. The
     * encoder reports the requested format back so Media3 keeps its 10-bit pipeline.
     */
    fun sdrEncoderFactory(context: Context, bitrate: Int, audio: Codec.EncoderFactory): Codec.EncoderFactory = object : Codec.EncoderFactory {
        override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
            audio.createForAudioEncoding(format, logSessionId)

        override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
            val info = hdrEncoder() ?: throw ExportException.createForCodec(
                IllegalStateException("No HEVC encoder with 10-bit surface input"),
                ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                ExportException.CodecInfo(format.toString(), true, false, null),
            )
            val caps = info.getCapabilitiesForType(MimeTypes.VIDEO_H265)
            val main10 = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            val level = caps.profileLevels.filter { it.profile == main10 }.maxOfOrNull { it.level }
            val media = MediaFormat.createVideoFormat(MimeTypes.VIDEO_H265, format.width, format.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010)
                setInteger(MediaFormat.KEY_PROFILE, main10)
                level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
                setInteger(MediaFormat.KEY_BIT_RATE, caps.videoCapabilities.bitrateRange.clamp(bitrate))
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, kotlin.math.round(format.frameRate.takeIf { it > 0 } ?: 30f).toInt())
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            return DefaultCodec(context, format, media, info.name, false, null)
        }

        override fun videoNeedsEncoding() = true
    }

    private fun hdrEncoder(): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && MimeTypes.VIDEO_H265 in info.supportedTypes &&
                MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010 in info.getCapabilitiesForType(MimeTypes.VIDEO_H265).colorFormats
        }.sortedByDescending { info ->
            android.os.Build.VERSION.SDK_INT >= 33 &&
                info.getCapabilitiesForType(MimeTypes.VIDEO_H265).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)
        }.firstOrNull()

    /** Asset loading that reads every SDR/log video track as HLG/BT.2020 (HLG stays as it is). */
    fun assetLoaderFactory(context: Context): AssetLoader.Factory {
        val extractors = ExtractorsFactory { DefaultExtractorsFactory().createExtractors().map { HlgLabelling(it) }.toTypedArray() }
        return DefaultAssetLoaderFactory(
            context,
            DefaultDecoderFactory.Builder(context).build(),
            Clock.DEFAULT,
            DefaultMediaSourceFactory(context, extractors),
            DataSourceBitmapLoader(context),
        )
    }

    private class HlgLabelling(private val inner: Extractor) : Extractor {
        override fun sniff(input: ExtractorInput) = inner.sniff(input)
        override fun init(output: ExtractorOutput) = inner.init(LabellingOutput(output))
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = inner.read(input, seekPosition)
        override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
        override fun release() = inner.release()
        override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
    }

    private class LabellingOutput(private val inner: ExtractorOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput =
            inner.track(id, type).let { if (type == C.TRACK_TYPE_VIDEO) LabellingTrack(it) else it }
        override fun endTracks() = inner.endTracks()
        override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
    }

    private class LabellingTrack(private val inner: TrackOutput) : TrackOutput {
        override fun format(format: Format) {
            if (!MimeTypes.isVideo(format.sampleMimeType) || format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) return inner.format(format)
            val color = ColorInfo.Builder()
                .setColorSpace(C.COLOR_SPACE_BT2020)
                .setColorRange(format.colorInfo?.colorRange?.takeIf { it != Format.NO_VALUE } ?: C.COLOR_RANGE_LIMITED)
                .setColorTransfer(C.COLOR_TRANSFER_HLG)
                .build()
            inner.format(format.buildUpon().setColorInfo(color).build())
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) =
            inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = inner.sampleData(data, length, sampleDataPart)
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        override fun durationUs(durationUs: Long) = inner.durationUs(durationUs)
    }
}

/**
 * What a clip is on the HDR route: SDR or log footage labelled HLG (its Y'CbCr matrix BT.709, or
 * BT.2020 as in Samsung Log), or real HLG, which is brought to SDR white with a highlight roll-off.
 */
enum class HdrInput { RELABELLED_BT709, RELABELLED_BT2020, HLG }

/**
 * The finished picture (display BT.709) for Media3's HLG output stage, which applies the HLG OETF.
 * With [hlgWhite] it becomes linear BT.2020 light with white at that HLG level (75 % = BT.2408).
 * Without, the values are only pre-distorted so the stage writes them back unchanged: a 10-bit
 * BT.709 SDR picture, which [HdrRoute.sdrEncoderFactory] encodes and tags as such.
 */
@OptIn(UnstableApi::class)
class HdrOutputEffect(private val hlgWhite: Float?) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(useHdr, hlgWhite?.let { HdrRoute.hlgEotf(it) } ?: 0f)

    private class Program(useHdr: Boolean, private val white: Float) : BaseGlShaderProgram(useHdr, 1) {
        private val program = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatUniform("uWhite", white)
                program.setIntUniform("uSdr", if (white == 0f) 1 else 0)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        companion object {
            const val VERTEX = """
                attribute vec4 aFramePosition;
                varying vec2 vTexSamplingCoord;
                void main() {
                    gl_Position = aFramePosition;
                    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
                }
            """

            // BT.1886 display EOTF (gamma 2.4), BT.709 → BT.2020 primaries, scaled so white lands on 75 % HLG.
            const val FRAGMENT = """
                precision highp float;
                uniform sampler2D uTexSampler;
                uniform float uWhite;
                uniform int uSdr;
                varying vec2 vTexSamplingCoord;
                const mat3 BT709_TO_BT2020 = mat3(
                    0.6274, 0.0691, 0.0164,
                    0.3293, 0.9195, 0.0880,
                    0.0433, 0.0114, 0.8956);
                float hlgEotf(float x) {
                    return x <= 0.5 ? x * x / 3.0 : (0.28466892 + exp((x - 0.55991073) / 0.17883277)) / 12.0;
                }
                void main() {
                    vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
                    vec3 c = clamp(src.rgb, 0.0, 1.0);
                    if (uSdr == 1) {
                        gl_FragColor = vec4(hlgEotf(c.r), hlgEotf(c.g), hlgEotf(c.b), src.a);
                    } else {
                        gl_FragColor = vec4(BT709_TO_BT2020 * pow(c, vec3(2.4)) * uWhite, src.a);
                    }
                }
            """
        }
    }
}
