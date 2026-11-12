package app.headsup.export

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.roundToInt

/**
 * A clip fitted inside a frame of another shape, its bars filled with the picture itself: a soft
 * cloud of its colours covering the frame (as YouTube's ambient light), the picture placed on top
 * as [toFrame] puts it (source → frame, both in Media3's normalised coordinates). Replaces the
 * plain framing matrix for such clips; the output is [outWidth]×[outHeight].
 *
 * The cloud is made small and smooth, as a blur that size should be: the picture covering the
 * frame is averaged down to [SMALL] pixels across, blurred there (a Gaussian, across then down),
 * and drawn back up with a smooth (B-spline) filter, so it has neither streaks nor blocks.
 */
@OptIn(UnstableApi::class)
class BlurFillEffect(
    /** Source → frame at a moment of the clip (its own time, ms): one matrix, or one per frame for a moving frame. */
    private val toFrameAt: (positionMs: Long) -> android.graphics.Matrix,
    private val outWidth: Int,
    private val outHeight: Int,
) : GlEffect {
    constructor(toFrame: android.graphics.Matrix, outWidth: Int, outHeight: Int) : this({ _ -> toFrame }, outWidth, outHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(private val useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val down = program(DOWN)
        private val blur = program(BLUR)
        private val final = program(FINAL)
        private val toSource = FloatArray(9)
        private var cover = floatArrayOf(1f, 1f)
        private var smallW = SMALL
        private var smallH = SMALL
        // Two small textures, blurred from one into the other and back.
        private var textures = IntArray(0)
        private var fbos = IntArray(0)

        private var firstUs = -1L

        /** The frame → source matrix for this moment, column by column as GLSL reads a mat3. */
        private fun sourceAt(presentationTimeUs: Long) {
            if (firstUs < 0) firstUs = presentationTimeUs
            val inverse = android.graphics.Matrix()
            toFrameAt((presentationTimeUs - firstUs) / 1000L).invert(inverse)
            val m = FloatArray(9).also { inverse.getValues(it) }
            floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]).copyInto(toSource)
        }

        private fun program(fragment: String): GlProgram = try {
            GlProgram(VERTEX, fragment).also {
                it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            val frame = outWidth.toFloat() / outHeight
            val picture = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
            // The part of the picture that covers the frame (fractions of its width and height).
            cover = if (frame > picture) floatArrayOf(1f, picture / frame) else floatArrayOf(frame / picture, 1f)
            // The cloud, small, with the frame's shape.
            if (frame >= 1f) {
                smallW = SMALL
                smallH = (SMALL / frame).roundToInt().coerceAtLeast(4)
            } else {
                smallH = SMALL
                smallW = (SMALL * frame).roundToInt().coerceAtLeast(4)
            }
            try {
                releaseSmall()
                textures = IntArray(2) { GlUtil.createTexture(smallW, smallH, useHdr) }
                fbos = IntArray(2) { GlUtil.createFboForTexture(textures[it]) }
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            return Size(outWidth, outHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                sourceAt(presentationTimeUs)
                // Where Media3 wants the frame drawn: put back after the small passes.
                val bound = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
                val viewport = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }

                // 1. The picture covering the frame, averaged down to the small size.
                GlUtil.focusFramebufferUsingCurrentContext(fbos[0], smallW, smallH)
                down.use()
                down.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                down.setFloatsUniform("uCover", cover)
                down.setFloatsUniform("uSmall", floatArrayOf(smallW.toFloat(), smallH.toFloat()))
                down.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                // 2. Blurred across, then down.
                for ((from, to, dir) in listOf(Triple(0, 1, floatArrayOf(1f / smallW, 0f)), Triple(1, 0, floatArrayOf(0f, 1f / smallH)))) {
                    GlUtil.focusFramebufferUsingCurrentContext(fbos[to], smallW, smallH)
                    blur.use()
                    blur.setSamplerTexIdUniform("uTexSampler", textures[from], 0)
                    blur.setFloatsUniform("uStep", dir)
                    blur.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }

                // 3. The frame: the picture where the framing puts it, the cloud behind.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound)
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
                final.use()
                final.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                final.setSamplerTexIdUniform("uCloud", textures[0], 1)
                final.setFloatsUniform("uToSource", toSource)
                final.setFloatsUniform("uSmall", floatArrayOf(smallW.toFloat(), smallH.toFloat()))
                final.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun releaseSmall() {
            for (f in fbos) GlUtil.deleteFbo(f)
            for (t in textures) GlUtil.deleteTexture(t)
            fbos = IntArray(0)
            textures = IntArray(0)
        }

        override fun release() {
            super.release()
            try {
                releaseSmall()
                down.delete()
                blur.delete()
                final.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private companion object {
        /** The cloud's long side, in pixels, before it is drawn back up. */
        const val SMALL = 48

        const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        // Each small pixel: the average of its whole cell of the picture (8 × 8 taps, filtered).
        val DOWN = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uCover;
            uniform vec2 uSmall;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec2 cell = uCover / uSmall;
                vec2 corner = (vTexSamplingCoord - 0.5) * uCover + 0.5 - cell * 0.5;
                vec3 acc = vec3(0.0);
                for (int j = 0; j < 8; j++) {
                    for (int i = 0; i < 8; i++) {
                        vec2 uv = corner + (vec2(float(i), float(j)) + 0.5) / 8.0 * cell;
                        acc += texture2D(uTexSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
                    }
                }
                gl_FragColor = vec4(acc / 64.0, 1.0);
            }
        """

        // A Gaussian (sigma 2.5 small pixels) along one direction.
        val BLUR = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uStep;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec3 acc = vec3(0.0);
                float sum = 0.0;
                for (int i = -6; i <= 6; i++) {
                    float w = exp(-float(i * i) / 12.5);
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + float(i) * uStep, vec2(0.0), vec2(1.0))).rgb * w;
                    sum += w;
                }
                gl_FragColor = vec4(acc / sum, 1.0);
            }
        """

        // The picture where the framing puts it; elsewhere the cloud, drawn up with a cubic
        // B-spline (four filtered reads), colours a little richer and a touch darker.
        val FINAL = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uCloud;
            uniform mat3 uToSource;
            uniform vec2 uSmall;
            varying vec2 vTexSamplingCoord;

            vec3 cloud(vec2 uv) {
                vec2 p = uv * uSmall - 0.5;
                vec2 f = fract(p);
                vec2 i = p - f;
                vec2 f2 = f * f;
                vec2 f3 = f2 * f;
                vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
                vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
                vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
                vec2 w3 = f3 / 6.0;
                vec2 s0 = w0 + w1;
                vec2 s1 = w2 + w3;
                // Between texel centres (k + 0.5) / size, weighted so one filtered read does two taps.
                vec2 t0 = clamp((i - 0.5 + w1 / s0) / uSmall, vec2(0.0), vec2(1.0));
                vec2 t1 = clamp((i + 1.5 + w3 / s1) / uSmall, vec2(0.0), vec2(1.0));
                vec3 a = texture2D(uCloud, vec2(t0.x, t0.y)).rgb;
                vec3 b = texture2D(uCloud, vec2(t1.x, t0.y)).rgb;
                vec3 c = texture2D(uCloud, vec2(t0.x, t1.y)).rgb;
                vec3 d = texture2D(uCloud, vec2(t1.x, t1.y)).rgb;
                return mix(mix(a, b, s1.x / (s0.x + s1.x)), mix(c, d, s1.x / (s0.x + s1.x)), s1.y / (s0.y + s1.y));
            }

            void main() {
                vec3 s = uToSource * vec3(vTexSamplingCoord * 2.0 - 1.0, 1.0);
                vec2 src = s.xy / s.z;
                if (abs(src.x) <= 1.0 && abs(src.y) <= 1.0) {
                    gl_FragColor = texture2D(uTexSampler, src * 0.5 + 0.5);
                    return;
                }
                vec3 c = cloud(vTexSamplingCoord);
                float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
                gl_FragColor = vec4(max(mix(vec3(l), c, 1.25) * 0.9, 0.0), 1.0);
            }
        """
    }
}
