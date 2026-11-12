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
import app.headsup.core.edit.GateWeave

/**
 * Film in a projector ([GateWeave]): the frame drifting a little from frame to frame (enlarged
 * just enough that its edges stay out of sight) and its light flickering. On the finished frame,
 * before the dashboard and texts; times as [CutEffect]'s, so it moves as the preview does.
 */
@OptIn(UnstableApi::class)
class GateWeaveEffect(
    private val weave: Float,
    private val flicker: Float,
    private val speed: Float,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private var firstUs = -1L

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                if (firstUs < 0) firstUs = presentationTimeUs
                val timelineMs = ((presentationTimeUs - firstUs) / 1000 / speed).toLong()
                val shift = GateWeave.at(timelineMs, weave, flicker)
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatUniform("uCover", GateWeave.cover(weave))
                program.setFloatUniform("uDx", shift.dx)
                // Texture rows run upwards: a move down the picture is down the texture's y.
                program.setFloatUniform("uDy", -shift.dy)
                program.setFloatUniform("uGain", shift.gain)
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

        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform float uCover;
            uniform float uDx;
            uniform float uDy;
            uniform float uGain;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec2 uv = (vTexSamplingCoord - 0.5) / uCover + 0.5 - vec2(uDx, uDy);
                vec3 c = texture2D(uTexSampler, clamp(uv, 0.0, 1.0)).rgb;
                gl_FragColor = vec4(c * uGain, 1.0);
            }
        """
    }
}
