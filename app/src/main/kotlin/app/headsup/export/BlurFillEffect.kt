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

/**
 * A clip fitted inside a frame of another shape, its bars filled with the picture itself: scaled
 * to cover the frame, blurred and a little darker (as social apps do), the picture placed on top as
 * [toFrame] puts it (source → frame, both in Media3's normalised coordinates). Replaces the plain
 * framing matrix for such clips; the output is [outWidth]×[outHeight].
 */
@OptIn(UnstableApi::class)
class BlurFillEffect(
    private val toFrame: android.graphics.Matrix,
    private val outWidth: Int,
    private val outHeight: Int,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program: GlProgram = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private val toSource = FloatArray(9)
        private var cover = floatArrayOf(1f, 1f)
        private var aspect = 1f

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            val inverse = android.graphics.Matrix()
            toFrame.invert(inverse)
            val m = FloatArray(9).also { inverse.getValues(it) }
            // Column by column, as GLSL reads a mat3.
            floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]).copyInto(toSource)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            val frame = outWidth.toFloat() / outHeight
            val picture = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
            // The part of the picture that covers the frame (fractions of its width and height).
            cover = if (frame > picture) floatArrayOf(1f, picture / frame) else floatArrayOf(frame / picture, 1f)
            aspect = picture
            return Size(outWidth, outHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform("uToSource", toSource)
                program.setFloatsUniform("uCover", cover)
                program.setFloatUniform("uAspect", aspect)
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
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform mat3 uToSource;
            uniform vec2 uCover;
            uniform float uAspect;
            varying vec2 vTexSamplingCoord;

            void main() {
                // The picture where the framing puts it.
                vec3 s = uToSource * vec3(vTexSamplingCoord * 2.0 - 1.0, 1.0);
                vec2 src = s.xy / s.z;
                if (abs(src.x) <= 1.0 && abs(src.y) <= 1.0) {
                    gl_FragColor = texture2D(uTexSampler, src * 0.5 + 0.5);
                    return;
                }
                // Behind it: the picture covering the frame, blurred over 6 % of its height (a
                // spiral of taps, nearer ones counting more) and a little darker.
                vec2 uv = (vTexSamplingCoord - 0.5) * uCover + 0.5;
                vec3 acc = vec3(0.0);
                float sum = 0.0;
                for (int i = 0; i < 64; i++) {
                    float t = (float(i) + 0.5) / 64.0;
                    float a = float(i) * 2.39996;
                    vec2 o = vec2(cos(a) / uAspect, sin(a)) * sqrt(t) * 0.06;
                    float w = 1.0 - 0.7 * t;
                    acc += texture2D(uTexSampler, clamp(uv + o, vec2(0.0), vec2(1.0))).rgb * w;
                    sum += w;
                }
                gl_FragColor = vec4(acc / sum * 0.8, 1.0);
            }
        """
    }
}
